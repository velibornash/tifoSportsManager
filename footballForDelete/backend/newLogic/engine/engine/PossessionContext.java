package org.example.footballmanager.newLogic.engine;

public class PossessionContext {

    private long ownerId;
    private String ownerTeamSide;
    private double possessionTime;
    private MatchPhase phase;
    private int passCount;
    private long chainId;

    public PossessionContext() {
        this.ownerId = -1;
        this.ownerTeamSide = null;
        this.possessionTime = 0;
        this.phase = MatchPhase.BUILD_UP;
        this.passCount = 0;
        this.chainId = 0;
    }

    public void reset(long ownerId, String ownerTeamSide) {
        this.ownerId = ownerId;
        this.ownerTeamSide = ownerTeamSide;
        this.possessionTime = 0;
        this.phase = MatchPhase.BUILD_UP;
        this.passCount = 0;
        this.chainId++;
    }

    public void update(double deltaTime) {
        this.possessionTime += deltaTime;
        updatePhase();
    }

    private void updatePhase() {
        // Phase is updated externally based on ball position
    }

    public long getOwnerId() { return ownerId; }
    public String getOwnerTeamSide() { return ownerTeamSide; }
    public double getPossessionTime() { return possessionTime; }
    public MatchPhase getPhase() { return phase; }
    public int getPassCount() { return passCount; }
    public long getChainId() { return chainId; }

    public void setPhase(MatchPhase phase) { this.phase = phase; }
    public void incrementPassCount() { this.passCount++; }
    public void setChainId(long chainId) { this.chainId = chainId; }
}
