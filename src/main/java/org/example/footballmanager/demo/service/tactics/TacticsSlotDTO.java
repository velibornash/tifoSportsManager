package org.example.footballmanager.demo.service.tactics;

/**
 * DTO for a tactical formation slot — maps a slot key (e.g. "GK", "DCL") to its anchor cell.
 * Self-contained within the service package; no dependency on newLogic.
 */
public class TacticsSlotDTO {
    private String slotKey;
    private String label;
    private String role;
    private String line;
    private int order;
    private String anchorCellKey;

    public TacticsSlotDTO() {}

    public TacticsSlotDTO(String slotKey, String label, String role, String line, int order, String anchorCellKey) {
        this.slotKey = slotKey;
        this.label = label;
        this.role = role;
        this.line = line;
        this.order = order;
        this.anchorCellKey = anchorCellKey;
    }

    public String getSlotKey() { return slotKey; }
    public String getLabel() { return label; }
    public String getRole() { return role; }
    public String getLine() { return line; }
    public int getOrder() { return order; }
    public String getAnchorCellKey() { return anchorCellKey; }
}
