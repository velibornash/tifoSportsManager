package org.example.footballmanager.demo;

/**
 * Eksplicitan model TEKUCE akcije simulacije.
 *
 * Akcija poseduje:
 *  - tip ({@link Type}: CHASE / CARRY / PASS / SHOT)
 *  - igraca koji je izvodi (nosioc / jurioc / dodavac / suter)
 *  - ciljnog igraca kad je primenjivo (primaoc pasa)
 *  - ciljnu poziciju kad je primenjivo (meta pasa / suta)
 *  - zivotni ciklus (da li je lopta u letu kod PASS/SHOT)
 *
 * EXECUTION QUALITY ( PASS i SHOT):
 *  - skill — generisan demo skill (1-20), zamenice se pravim skillom kasnije
 *  - intendedTarget — pozicija gde je akcija BILA zamišljena
 *  - actualTarget — pozicija gde lopta ZAISTA leti (odstupanje od skill-a)
 *  - goodExecution — da li je izvedba dovoljno dobra za normalan ishod
 */
public class Action {

    public enum SaveType { NONE, FIELD_REBOUND, CORNER_REBOUND }

    public enum Type {
        CHASE,
        CARRY,
        PASS,
        SHOT
    }

    private final Type type;
    private final Player actingPlayer;
    private Player targetPlayer;
    private Position targetPosition;
    private final boolean inFlight;

    // --- execution quality (PASS / SHOT) ---
    private int skill;
    private Position intendedTarget;
    private Position actualTarget;
    private boolean goodExecution;
    private SaveType saveType = SaveType.NONE;
    private boolean clearance;

    public Action(Type type, Player actingPlayer) {
        this.type = type;
        this.actingPlayer = actingPlayer;
        this.inFlight = type == Type.PASS || type == Type.SHOT;
    }

    public Type getType() {
        return type;
    }

    /** Igrac koji izvodi akciju (nosioc / jurioc / dodavac / suter). */
    public Player getActingPlayer() {
        return actingPlayer;
    }

    /** Ciljni igrac (primaoc pasa) — samo za PASS. */
    public Player getTargetPlayer() {
        return targetPlayer;
    }

    public void setTargetPlayer(Player targetPlayer) {
        this.targetPlayer = targetPlayer;
    }

    /** Ciljna pozicija (meta pasa / suta) — kad je primenjivo. */
    public Position getTargetPosition() {
        return targetPosition;
    }

    public void setTargetPosition(Position targetPosition) {
        this.targetPosition = targetPosition;
    }

    /** Da li je lopta u letu (PASS/SHOT). */
    public boolean isInFlight() {
        return inFlight;
    }

    /** Lopta u letu ka primaocu (PASS u toku). */
    public boolean isPassInFlight() {
        return inFlight && type == Type.PASS;
    }

    /** Lopta u letu ka golu (SHOT u toku). */
    public boolean isShotInFlight() {
        return inFlight && type == Type.SHOT;
    }

    // --- execution quality getters / setters ---

    public int getSkill() {
        return skill;
    }

    public void setSkill(int skill) {
        this.skill = skill;
    }

    /** Pozicija gde je lopta trebala da ide (primaoc za PASS, gol za SHOT). */
    public Position getIntendedTarget() {
        return intendedTarget;
    }

    public void setIntendedTarget(Position intendedTarget) {
        this.intendedTarget = intendedTarget;
    }

    /** Pozicija gde lopta zaista leti (odstupanje od skill-a). */
    public Position getActualTarget() {
        return actualTarget;
    }

    public void setActualTarget(Position actualTarget) {
        this.actualTarget = actualTarget;
    }

    /** Da li je izvedba dovoljno dobra za normalan ishod (gol / primaoc hvata). */
    public boolean isGoodExecution() {
        return goodExecution;
    }

    public void setGoodExecution(boolean goodExecution) {
        this.goodExecution = goodExecution;
    }

    public SaveType getSaveType() { return saveType; }
    public void setSaveType(SaveType saveType) { this.saveType = saveType; }
    public boolean isClearance() { return clearance; }
    public void setClearance(boolean clearance) { this.clearance = clearance; }

    @Override
    public String toString() {
        return type + " by " + actingPlayer.getLabel();
    }
}
