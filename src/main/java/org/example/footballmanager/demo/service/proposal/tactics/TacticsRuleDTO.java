package org.example.footballmanager.demo.service.proposal.tactics;

/**
 * DTO for a single tactical rule — maps (slot, ball state, possession) → target cell.
 * Self-contained within the proposal package.
 *
 * KEY CONVENTION: the initial tactical editor that produced this data
 * indexes ROWS and COLUMNS FROM 0 (0-based), not 1. A target key like
 * "CELL_1_3" means editor cell row 1, col 3 — which on the physical field
 * is cell row 2 (rows 2-3, centre 2.5) and col 4 (cols 4-5, centre 4.5).
 * TacticsRules.parseCell() performs the 0-to-1 conversion on load.
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