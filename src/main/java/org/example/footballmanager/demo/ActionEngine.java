package org.example.footballmanager.demo;

import java.util.List;
import java.util.Locale;

/**
 * Odgovornost: ZIVOTNI CIKLUS AKCIJE.
 *
 * Pokrece akcije (CHASE/CARRY/PASS/SHOT), izvrsava izabrane odluke
 * (pas / kretanje / sut), prati kraj akcije, hvatanje pasa, gol i proslavu.
 *
 * PASS i SHOT imaju execution quality: generise se demo skill (1-20),
 * lopta leti ka odstupnoj meti, ishod zavisi od kvaliteta izvodjenja.
 */
public class ActionEngine {

    public static final int SHOOT_MIN_ROW = 5;                    // iz kog reda nosilac moze na gol (ne menja se)
    public static final Position GOAL_POSITION = new Position(7, 3.5); // away gol — linija gola je red 7

    private final SimulationState state;
    private final PlayerSelectionEngine selection;
    private final ExecutionQuality executionQuality;

    public ActionEngine(SimulationState state, PlayerSelectionEngine selection,
                        ExecutionQuality executionQuality) {
        this.state = state;
        this.selection = selection;
        this.executionQuality = executionQuality;
    }

    /** Startuje novu akciju: postavlja Action, status i log. */
    public void start(Action.Type type, String description) {
        state.setAction(new Action(type, state.getCarrier()));
        state.setStatus(description);
        state.log("Action started: " + description);
    }

    /** Zavrsava tekuci turn: cisti akciju i osvezava kraj pozicija. */
    public void complete(String description) {
        state.setAction(null);
        state.setRoundComplete(true);
        state.setRoundEndBallPosition(state.getBall().getPosition());
        for (Player p : state.getPlayers()) {
            state.setRoundEndPosition(p, p.getPosition());
        }
        state.log("Action completed: " + description);
    }

    /**
     * Odluka o pasu: nasumicni primaoc iz 6 najblizih HOME igraca.
     * Generise demo passing skill i racuna odstupnu metu.
     */
    public void executePass() {
        List<Player> nearest = selection.nearestHomeTo(state.getCarrier(), 6);
        if (nearest.isEmpty()) {
            executeCarry();
            return;
        }
        Player receiver = nearest.get(state.getRandom().nextInt(nearest.size()));
        executePassTo(receiver);
    }

    public void executePassTo(Player receiver) {
        receiver.setLocked(true);

        Position intendedTarget = receiver.getPosition();
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                state.getCarrier().getPosition(), intendedTarget, receiver);
        boolean actualOutside = isOutsidePitch(result.actualTarget());
        boolean received = result.received() && !actualOutside;
        // Kada je pas dovoljno dobar, lopta leti direktno do primaoca.
        // Odstupanje se koristi samo za los pas, pa nema naknadnog skretanja.
        Position flightTarget = received ? intendedTarget : outOfBoundsEndpoint(result.actualTarget());

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);

        String qualityLabel = received ? "GOOD" : "POOR";
        String description = "PASS: " + state.getCarrier().getLabel() + " -> " + receiver.getLabel()
                + " | passing: " + result.skill() + "/20 | " + qualityLabel
                + " | target: " + formatPosition(flightTarget);
        start(Action.Type.PASS, description);

        Action action = state.getAction();
        action.setTargetPlayer(receiver);
        action.setTargetPosition(intendedTarget);
        action.setSkill(result.skill());
        action.setIntendedTarget(intendedTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);
        state.incrementActionCount();
    }

    /** Ako pas predje bocnu liniju, animacija mora da zavrsi na col 0 ili 7. */
    private Position outOfBoundsEndpoint(Position target) {
        if (target.getColumn() < 1) {
            return new Position(MovementEngine.clamp(target.getRow(), 1, 7), 0);
        }
        if (target.getColumn() > 6) {
            return new Position(MovementEngine.clamp(target.getRow(), 1, 7), 7);
        }
        if (target.getRow() < 1) return new Position(0, target.getColumn());
        if (target.getRow() > 7) return new Position(8, target.getColumn());
        return target;
    }

    private boolean isOutsidePitch(Position position) {
        return position.getRow() < 1 || position.getRow() > 7
                || position.getColumn() < 1 || position.getColumn() > 6;
    }

    /** Odluka o kretanju: 1 celija (blagi nagib napred), lopta prati nosioca. */
    public void executeCarry() {
        Player carrier = state.getCarrier();
        double r = carrier.getPosition().getRow();
        double c = carrier.getPosition().getColumn();
        int dr = weightedForwardDr();
        int dc = state.getRandom().nextInt(3) - 1; // -1, 0, 1
        if (dr == 0 && dc == 0) {
            dr = 1;
        }
        double nr = MovementEngine.clamp(r + dr, 1, 7);
        double nc = MovementEngine.clamp(c + dc, 1, 6);
        carrier.setTarget(new Position(nr, nc));
        start(Action.Type.CARRY, "CARRY: " + carrier.getLabel() + " -> "
                + formatPosition(new Position(nr, nc)));
        state.incrementActionCount();
    }

    /** Smer po redovima: blagi nagib NAPRED (ka away golu): 50% +1, 25% 0, 25% -1. */
    private int weightedForwardDr() {
        int roll = state.getRandom().nextInt(100);
        if (roll < 50) return 1;
        if (roll < 75) return 0;
        return -1;
    }

/** Odluka o sutu: lopta leti ka away golu sa odstupanjem zavisnim od skill-a. */
    public void executeShot() {
        ExecutionQuality.ShotResult result = executionQuality.evaluateShot(GOAL_POSITION);

        state.getBall().setCarrier(null);
        // For a goal, set target to row 8 (through the goal) so animation shows ball flying through
        // For a miss, actualTarget already has the out-of-bounds position
        Position shotTarget = result.goal()
                ? new Position(8.0, 3.5)
                : new Position(8.0, result.actualTarget().getColumn());
        state.getBall().setTarget(shotTarget);

        String qualityLabel = result.goal() ? "GOOD" : "POOR";
        String description = "SHOT by " + state.getCarrier().getLabel()
                + " | striker: " + result.skill() + "/20 | " + qualityLabel
                + " | target: " + formatPosition(result.actualTarget());
        start(Action.Type.SHOT, description);

        Action action = state.getAction();
        action.setTargetPosition(GOAL_POSITION);
        action.setSkill(result.skill());
        action.setIntendedTarget(GOAL_POSITION);
        // For goals, set actualTarget to shotTarget so goal triggers when ball reaches row 8
        // For misses, use actualTarget for proper miss handling
        action.setActualTarget(result.goal() ? shotTarget : result.actualTarget());
        action.setGoodExecution(result.goal());
        state.incrementActionCount();
        state.incrementShotCount();
    }

    private static String formatPosition(Position position) {
        return "(" + String.format(Locale.ROOT, "%.2f", position.getRow())
                + "," + String.format(Locale.ROOT, "%.2f", position.getColumn()) + ")";
    }

    /** Primaoc hvata loptu — PASS se zavrsava, nosilac postaje primaoc. */
    public void pickupPass() {
        Player receiver = state.getAction().getTargetPlayer();
        receiver.setLocked(false);
        receiver.setTarget(null);
        state.getBall().setCarrier(receiver);
        state.setCarrier(receiver);
        state.getBall().setTarget(null);
        state.setStatus(receiver.getLabel() + " received pass");
        complete("PASS -> " + receiver.getLabel() + " | RECEIVED");
    }

    /** Gol je postignut — simulacija se zamrzava do reset-a. */
    public void goalScored() {
        state.incrementGoalCount();
        state.setCelebrating(true);
        state.setStatus("GOAL for HOME! (" + state.getGoalCount() + ")");
        state.log(state.getStatus());
        complete("SHOT (GOAL!) | striker: " + state.getAction().getSkill() + "/20");
        // Bez odmah reset(): demo prikazuje proslavu ~5s, pa tek onda reset.
    }

/**
 * Pass nije stigao do primaoca — lopta postaje LOOSE.
 * Nosilac = null, sledeca akcija ce automatski biti CHASE.
 */
public void passFailed() {
    Player receiver = state.getAction().getTargetPlayer();
    receiver.setLocked(false);
    state.getBall().setCarrier(null);
    state.getBall().setTarget(null);
    // Clear any lingering target on the carrier to prevent stuck detection loops
    if (state.getCarrier() != null) {
        state.getCarrier().setTarget(null);
    }
    state.setCarrier(null);
    state.setStatus("LOOSE BALL — pass missed");
    complete("PASS -> " + receiver.getLabel()
            + " | LOOSE BALL");
}

    /** Promasaj stize do reda 8; zatim AWAY golman izvodi restart. */
    public void shotMissed() {
        Position missPosition = state.getAction().getActualTarget();
        state.getBall().setPosition(missPosition != null ? missPosition : state.getBall().getPosition());
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        state.setStatus("SHOT missed — AWAY goalkeeper restart");
        complete("SHOT | striker: " + state.getAction().getSkill() + "/20"
                + " | MISS — ball reached row 8");
    }

    public void passOutOfBounds() {
        Player receiver = state.getAction().getTargetPlayer();
        if (receiver != null) receiver.setLocked(false);
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        state.setStatus("BALL OUT — AWAY throw-in restart");
        complete("PASS -> OUT OF BOUNDS");
    }

    /**
     * Detektuje kraj akcije na kraju tick-a:
     * CHASE/CARRY se zavrsavaju kad nosilac preuzme loptu / stigne na cilj.
     * PASS/SHOT se zavrsavaju u pickupPass()/goalScored()/passFailed()/shotMissed().
     */
    public void checkActionCompletion() {
        if (!state.hasActiveAction()) {
            return;
        }
        switch (state.getAction().getType()) {
            case CHASE -> {
                if (state.getCarrier() != null
                        && MovementEngine.distance(state.getCarrier().getPosition(),
                        state.getBall().getPosition()) <= 1e-9) {
                    state.getBall().setCarrier(state.getCarrier());
                    state.getCarrier().setTarget(null);
                    state.setActionDelayUntilMs(System.currentTimeMillis() + 750);
                    complete("CHASE: " + state.getCarrier().getLabel() + " has the ball");
                }
            }
            case CARRY -> {
                // If carrier has the ball and target is null, action completes
                if (state.getCarrier().getTarget() == null && state.getBall().getCarrier() == state.getCarrier()) {
                    complete("CARRY: " + state.getCarrier().getLabel());
                }
            }
            default -> { /* PASS/SHOT se zavrsavaju u pickupPass()/goalScored()/passFailed()/shotMissed() */ }
        }
    }
}
