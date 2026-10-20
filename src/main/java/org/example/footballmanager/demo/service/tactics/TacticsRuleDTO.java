package org.example.footballmanager.demo.service.tactics;

/**
 * DTO for a single tactical rule — maps (slot, ball state, possession) → target cell.
 * Self-contained within the service package; no dependency on newLogic.
 */
public class TacticsRuleDTO {
    private String slotKey;
    private String ballStateKey;
    private String possessionContext;
    private String targetCellKey;

    public TacticsRuleDTO() {}

    public TacticsRuleDTO(String slotKey, String ballStateKey, String possessionContext, String targetCellKey) {
        this.slotKey = slotKey;
        this.ballStateKey = ballStateKey;
        this.possessionContext = possessionContext;
        this.targetCellKey = targetCellKey;
    }

    public String getSlotKey() { return slotKey; }
    public void setSlotKey(String slotKey) { this.slotKey = slotKey; }
    public String getBallStateKey() { return ballStateKey; }
    public void setBallStateKey(String ballStateKey) { this.ballStateKey = ballStateKey; }
    public String getPossessionContext() { return possessionContext; }
    public void setPossessionContext(String possessionContext) { this.possessionContext = possessionContext; }
    public String getTargetCellKey() { return targetCellKey; }
    public void setTargetCellKey(String targetCellKey) { this.targetCellKey = targetCellKey; }
}
