package org.example.footballmanager.demo;

/**
 * Eksplicitan model TEKUCE akcije simulacije. Zamenjuje ranije rasute
 * primitive ({@code actionType}, {@code actionInProgress},
 * {@code pendingCarrier}, {@code shotInFlight}) jednim koherentnim objektom.
 *
 * Akcija poseduje:
 *  - tip ({@link Type}: CHASE / CARRY / PASS / SHOT)
 *  - igraca koji je izvodi (nosioc / jurioc / dodavac / suter)
 *  - ciljnog igraca kad je primenjivo (primaoc pasa)
 *  - ciljnu poziciju kad je primenjivo (meta pasa / suta)
 *  - zivotni ciklus (da li je lopta u letu kod PASS/SHOT)
 *
 * Bez ikakve ocene kvaliteta akcije, skillsa ili taktickog scoringa — samo
 * nosi podatke koji su simulaciji potrebni da izvrsi tekuci tok.
 */
public class Action {

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

    @Override
    public String toString() {
        return type + " by " + actingPlayer.getLabel();
    }
}
