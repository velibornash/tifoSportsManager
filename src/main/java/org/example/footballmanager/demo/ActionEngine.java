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
    public static final Position GOAL_EXIT_POSITION = new Position(8, 3.5);

    public static Position goalPositionFor(String team) {
        return SimulationState.TEAM_HOME.equals(team)
                ? GOAL_POSITION : new Position(1, 3.5);
    }

    public static Position goalExitPositionFor(String team) {
        return SimulationState.TEAM_HOME.equals(team)
                ? GOAL_EXIT_POSITION : new Position(0, 3.5);
    }

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
        if (type != Action.Type.CHASE) state.clearActiveChasers();
        Action action = new Action(type, state.getCarrier());
        action.setActionId(state.nextActionId());
        state.setAction(action);
        state.setStatus(description);
        state.getEventStore().append(new ActionStartedEvent(
                state.getSimulationTick(), state.getRound(), action.getActionId(),
                type, playerId(action.getActingPlayer()), description));
        state.log("Action started: " + description);
    }

    /** Zavrsava tekuci turn: cisti akciju i osvezava kraj pozicija. */
    public void complete(String description) {
        state.clearActiveChasers();
        state.setAction(null);
        state.setRoundComplete(true);
        state.setRoundEndBallPosition(state.getBall().getPosition());
        for (Player p : state.getPlayers()) {
            state.setRoundEndPosition(p, p.getPosition());
        }
        state.log("Action completed: " + description);
    }

    /** Records a blocked chase before handing pursuit to a replacement player. */
    public void completeBlockedChase() {
        Action action = state.getAction();
        if (action == null || action.getType() != Action.Type.CHASE) return;
        recordActionResult(ActionOutcome.CHASE_CONTINUE,
                state.getBall().getBallState(), state.getBall().getBallState(), null);
        complete("CHASE: " + action.getActingPlayer().getLabel()
                + " blocked, switching chaser");
    }

    /**
     * Odluka o pasu: nasumicni primaoc iz 6 najblizih HOME igraca.
     * Generise demo passing skill i racuna odstupnu metu.
     */
    public void executePass() {
        Player carrier = state.getCarrier();
        int candidateCount = "GK".equals(carrier.getRole()) ? 2 : 6;
        List<Player> nearest = selection.nearestTeamTo(carrier, candidateCount);
        if (nearest.isEmpty()) {
            executeClearance();
            return;
        }
        Player receiver = nearest.get(state.getRandom().nextInt(nearest.size()));
        // Hard rule: normal HOME play never returns the ball to row 1 or GK.
        // Restart-specific calls use executePassTo() directly and remain valid.
        if (isOwnGoalkeeperOrDefensiveRow(receiver, carrier.getTeam())) {
            executeClearance();
            return;
        }
        executePassTo(receiver);
    }

    public void executePassTo(Player receiver) {
        if (isOwnGoalkeeperOrDefensiveRow(receiver, state.getCarrier().getTeam())) {
            executeClearance();
            return;
        }
        receiver.setLocked(true);
        state.incrementPassAttempts(state.getCarrier().getTeam());

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
        if ("GK".equals(carrier.getRole())) {
            executeClearance();
            return;
        }
        double r = carrier.getPosition().getRow();
        double c = carrier.getPosition().getColumn();
        int dr = weightedForwardDr();
        int dc = state.getRandom().nextInt(3) - 1; // -1, 0, 1
        if (dr == 0 && dc == 0) {
            dr = 1;
        }
        double direction = SimulationState.TEAM_HOME.equals(carrier.getTeam()) ? 1 : -1;
        double nr = MovementEngine.clamp(r + direction * dr, 1, 7);
        double nc = MovementEngine.clamp(c + dc, 1, 6);
        carrier.setTarget(new Position(nr, nc));
        Position carryTarget = new Position(nr, nc);
        carrier.setTarget(carryTarget);
        start(Action.Type.CARRY, "CARRY: " + carrier.getLabel() + " -> "
                + formatPosition(carryTarget));
        state.getAction().setTargetPosition(carryTarget);
        state.incrementActionCount();
    }

    /**
     * After winning a dribble duel, the carrier visibly goes past the defender
     * instead of changing possession at the same point.
     */
    public void prepareDribbleBypass(Player defender) {
        Action action = state.getAction();
        Player carrier = state.getCarrier();
        if (action == null || carrier == null || defender == null) return;

        Position current = carrier.getPosition();
        Position finalTarget = action.getTargetPosition() != null
                ? action.getTargetPosition() : carrier.getTarget();
        if (finalTarget == null) return;

        double forwardRow = finalTarget.getRow() - current.getRow();
        double forwardCol = finalTarget.getColumn() - current.getColumn();
        double length = Math.hypot(forwardRow, forwardCol);
        if (length < 1e-9) {
            forwardRow = 1.0;
            forwardCol = 0.0;
            length = 1.0;
        }
        forwardRow /= length;
        forwardCol /= length;

        double toDefenderRow = defender.getPosition().getRow() - current.getRow();
        double toDefenderCol = defender.getPosition().getColumn() - current.getColumn();
        double sideRow = -forwardCol;
        double sideCol = forwardRow;
        double side = toDefenderRow * sideRow + toDefenderCol * sideCol;
        if (side > 0) {
            sideRow = -sideRow;
            sideCol = -sideCol;
        }

        Position bypass = new Position(
                MovementEngine.clamp(defender.getPosition().getRow() + forwardRow * 0.5 + sideRow * 0.42, 1, 7),
                MovementEngine.clamp(defender.getPosition().getColumn() + forwardCol * 0.5 + sideCol * 0.42, 1, 6));
        action.setDribbleBypassTarget(bypass);
        carrier.setTarget(bypass);
        state.log("DRIBBLE bypass: " + carrier.getLabel() + " past "
                + defender.getLabel() + " -> " + formatPosition(bypass));
    }

    /** Smer po redovima: blagi nagib NAPRED (ka away golu): 50% +1, 25% 0, 25% -1. */
    private int weightedForwardDr() {
        int roll = state.getRandom().nextInt(100);
        if (roll < 50) return 1;
        if (roll < 75) return 0;
        return -1;
    }

    /** Clearance ide nekoliko redova napred, ka AWAY golu, pa lopta ostaje loose. */
    public void executeClearance() {
        Player carrier = state.getCarrier();
        Position current = carrier.getPosition();
        double direction = SimulationState.TEAM_HOME.equals(carrier.getTeam()) ? 1 : -1;
        double targetRow = MovementEngine.clamp(current.getRow()
                + direction * (2.0 + state.getRandom().nextInt(3)), 1, 7);
        Position target = new Position(targetRow, 1.0 + state.getRandom().nextInt(6));
        state.getBall().setCarrier(null);
        state.getBall().setTarget(target);
        start(Action.Type.PASS, "CLEAR: " + carrier.getLabel() + " -> "
                + formatPosition(target));
        Action action = state.getAction();
        action.setClearance(true);
        action.setActualTarget(target);
        action.setIntendedTarget(target);
        action.setGoodExecution(true);
        state.incrementActionCount();
    }

/** Odluka o sutu: lopta leti ka away golu sa odstupanjem zavisnim od skill-a. */
    public void executeShot() {
        Position shotOrigin = state.getCarrier().getPosition();
        String shootingTeam = state.getCarrier().getTeam();
        Position goalPosition = goalPositionFor(shootingTeam);
        Position goalExit = goalExitPositionFor(shootingTeam);
        ExecutionQuality.ShotResult result = executionQuality.evaluateShot(goalPosition);

        state.getBall().setCarrier(null);
        // For a goal, set target to row 8 (through the goal) so animation shows ball flying through
        // For a miss, actualTarget already has the out-of-bounds position
        Position shotTarget = result.goal()
                ? goalExit
                : new Position(goalExit.getRow(), result.actualTarget().getColumn());
        state.getBall().setTarget(shotTarget);

        String qualityLabel = result.goal() ? "GOOD" : "POOR";
        String description = "SHOT by " + state.getCarrier().getLabel()
                + " | striker: " + result.skill() + "/20 | " + qualityLabel
                + " | target: " + formatPosition(result.actualTarget());
        start(Action.Type.SHOT, description);

        Action action = state.getAction();
        action.setTargetPosition(goalPosition);
        action.setExecutionOrigin(shotOrigin);
        action.setLogicalGoalPosition(goalPosition);
        action.setSkill(result.skill());
        action.setIntendedTarget(goalPosition);
        // Dobar šut prvo mora fizički da stigne do gol-linije. Tek posle
        // duela sa GK lopta nastavlja kroz gol do reda 8.
        action.setActualTarget(shotTarget);
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
        Ball.BallState previousState = state.getBall().getBallState();
        receiver.setLocked(false);
        receiver.setTarget(null);
        state.getBall().setCarrier(receiver);
        state.setCarrier(receiver);
        state.getBall().setTarget(null);
        state.setStatus(receiver.getLabel() + " received pass");
        state.setActionDelayTicks(SimulationState.ACTION_PAUSE_TICKS);
        state.incrementPassCompletions(receiver.getTeam());
        recordActionResult(ActionOutcome.PASS_COMPLETED, previousState, null, null);
        complete("PASS -> " + receiver.getLabel() + " | RECEIVED");
    }

    /** Duel winner takes the ball; used by CHASE/CARRY/RECEIVE resolution. */
    public void giveBallTo(Player winner, String reason) {
        Action action = state.getAction();
        Ball.BallState previousState = state.getBall().getBallState();
        if (action != null && action.getTargetPlayer() != null) {
            action.getTargetPlayer().setLocked(false);
        }
        state.getBall().setTarget(null);
        state.getBall().setPosition(winner.getPosition());
        state.getBall().setCarrier(winner);
        state.setCarrier(winner);
        winner.setTarget(null);
        state.setActionDelayTicks(SimulationState.ACTION_PAUSE_TICKS);
        ActionOutcome outcome = action == null ? ActionOutcome.CHASE_POSSESSION
                : switch (action.getType()) {
                    case CHASE -> ActionOutcome.CHASE_POSSESSION;
                    case CARRY -> ActionOutcome.CARRY_DUEL_LOST;
                    case PASS -> ActionOutcome.PASS_DUEL_LOST;
                    case SHOT -> ActionOutcome.SHOT_SAVE;
                };
        recordActionResult(outcome, previousState, null, winner.getLabel());
        complete("DUEL: " + winner.getLabel() + " wins | " + reason);
    }

    /** AWAY ne napada ka svom golu posle osvojenog duela: čisti loptu. */
    public void executeAwayClearance(Player winner) {
        Position current = winner.getPosition();
        double targetRow = Math.max(1.0, current.getRow() - 1.0 - state.getRandom().nextInt(3));
        Position target = new Position(targetRow, 1.0 + state.getRandom().nextInt(6));
        state.setReturningPlayer(winner);
        state.setRoundComplete(false);
        winner.setTarget(winner.getAlternativePosition());
        state.getBall().setCarrier(null);
        state.getBall().setTarget(target);
        start(Action.Type.PASS, "CLEARANCE: " + winner.getLabel() + " -> "
                + formatPosition(target));
        Action action = state.getAction();
        action.setClearance(true);
        action.setActualTarget(target);
        action.setIntendedTarget(target);
        action.setGoodExecution(true);
        state.incrementActionCount();
    }

    public void finishAwayClearance() {
        Ball.BallState previousState = state.getBall().getBallState();
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        recordActionResult(ActionOutcome.CLEAR_LOOSE, previousState, null, null);
        complete("CLEARANCE -> LOOSE BALL");
    }

    /** Good shot lost to the goalkeeper duel; starts a smooth rebound sequence. */
    public void shotSaved(Player goalkeeper) {
        Action action = state.getAction();
        state.incrementShotsOnTarget(action.getActingPlayer().getTeam());
        Ball.BallState previousState = state.getBall().getBallState();
        state.getBall().setCarrier(null);
        Position goalPosition = goalPositionFor(action.getActingPlayer().getTeam());
        state.getBall().setPosition(goalPosition);
        boolean corner = state.getRandom().nextInt(3) == 2;
        if (corner) {
            boolean right = state.getRandom().nextBoolean();
            action.setSaveType(Action.SaveType.CORNER_REBOUND);
            int cornerRow = SimulationState.TEAM_HOME.equals(action.getActingPlayer().getTeam()) ? 8 : 0;
            action.setActualTarget(new Position(cornerRow, right ? 6 : 1));
            state.getBall().setTarget(action.getActualTarget());
            state.setStatus("SHOT saved — GK deflects to " + (right ? "right" : "left") + " corner");
        } else {
            action.setSaveType(Action.SaveType.FIELD_REBOUND);
            int reboundRow = SimulationState.TEAM_HOME.equals(action.getActingPlayer().getTeam())
                    ? 5 + state.getRandom().nextInt(3)
                    : 1 + state.getRandom().nextInt(3);
            Position rebound = new Position(reboundRow, 1 + state.getRandom().nextInt(6));
            action.setActualTarget(rebound);
            state.getBall().setTarget(rebound);
            state.setStatus("SHOT saved — ball rebounds into field");
        }
        state.log("SHOT outcome: SAVE | GK: " + goalkeeper.getLabel()
                + " | variant: " + action.getSaveType());
        recordActionResult(ActionOutcome.SHOT_SAVE, previousState, null, goalkeeper.getLabel());
        completeSaveContactLogOnly(goalkeeper);
    }

    private void completeSaveContactLogOnly(Player goalkeeper) {
        // Akcija ostaje aktivna dok lopta ne završi rebound putanju.
        state.setCarrier(null);
        state.log("Action completed: SHOT | SAVE contact: " + goalkeeper.getLabel());
    }

    public void finishFieldRebound() {
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);
        state.setStatus("SAVE rebound — LOOSE BALL");
        complete("SHOT | SAVE rebound into field");
    }

    public void finishCornerRebound() {
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);
        state.setStatus("SAVE rebound — CORNER");
        complete("SHOT | SAVE rebound to corner");
    }

    /** Nastavlja do reda 8 tek kada je šutant dobio duel sa golmanom. */
    public void continueGoalAfterGkDuel() {
        Action action = state.getAction();
        action.setGoalLineResolved(true);
        state.log("SHOT outcome: GOAL path | ball crosses goal line and continues to row 8");
    }

    /** Gol je postignut — simulacija se zamrzava do reset-a. */
    public void goalScored() {
        Ball.BallState previousState = state.getBall().getBallState();
        Player scorer = state.getAction().getActingPlayer();
        recordActionResult(ActionOutcome.SHOT_GOAL, previousState, Ball.BallState.LOOSE, null);
        state.incrementShotsOnTarget(scorer.getTeam());
        state.recordGoal(scorer);
        if (SimulationState.TEAM_HOME.equals(scorer.getTeam())) {
            state.incrementGoalCount();
        } else {
            state.incrementAwayGoalCount();
        }
        state.setKickoffTeam(SimulationState.TEAM_HOME.equals(scorer.getTeam())
                ? "AWAY" : SimulationState.TEAM_HOME);
        state.setCelebrating(true);
        int score = SimulationState.TEAM_HOME.equals(scorer.getTeam())
                ? state.getGoalCount() : state.getAwayGoalCount();
        state.setStatus("GOAL for " + scorer.getTeam() + "! (" + score + ")");
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
    Ball.BallState previousState = state.getBall().getBallState();
    receiver.setLocked(false);
    state.getBall().setCarrier(null);
    state.getBall().setTarget(null);
    // Clear any lingering target on the carrier to prevent stuck detection loops
    if (state.getCarrier() != null) {
        state.getCarrier().setTarget(null);
    }
    state.setCarrier(null);
    state.setStatus("LOOSE BALL — pass missed");
    recordActionResult(ActionOutcome.PASS_LOOSE, previousState, null, null);
    complete("PASS -> " + receiver.getLabel()
            + " | LOOSE BALL");
}

    /** Promasaj stize do reda 8; zatim AWAY golman izvodi restart. */
    public void shotMissed() {
        Ball.BallState previousState = state.getBall().getBallState();
        Position missPosition = state.getAction().getActualTarget();
        state.getBall().setPosition(missPosition != null ? missPosition : state.getBall().getPosition());
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        state.setStatus("SHOT missed — AWAY goalkeeper restart");
        recordActionResult(ActionOutcome.SHOT_MISS, previousState, null, null);
        complete("SHOT | striker: " + state.getAction().getSkill() + "/20"
                + " | MISS — ball reached row 8");
    }

    public void passOutOfBounds() {
        Player receiver = state.getAction().getTargetPlayer();
        Ball.BallState previousState = state.getBall().getBallState();
        if (receiver != null) receiver.setLocked(false);
        state.getBall().setCarrier(null);
        state.getBall().setTarget(null);
        state.setCarrier(null);
        state.setStatus("BALL OUT — AWAY throw-in restart");
        recordActionResult(ActionOutcome.PASS_OUT, previousState, null, null);
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
                    state.setActionDelayTicks(SimulationState.ACTION_PAUSE_TICKS);
                    recordActionResult(ActionOutcome.CHASE_POSSESSION,
                            Ball.BallState.LOOSE, null, null);
                    complete("CHASE: " + state.getCarrier().getLabel() + " has the ball");
                }
            }
            case CARRY -> {
                // If carrier has the ball and target is null, action completes
                if (state.getCarrier().getTarget() == null && state.getBall().getCarrier() == state.getCarrier()) {
                    state.setActionDelayTicks(SimulationState.ACTION_PAUSE_TICKS);
                    recordActionResult(ActionOutcome.CARRY_COMPLETED,
                            Ball.BallState.IN_POSSESSION, null, null);
                    complete("CARRY: " + state.getCarrier().getLabel());
                }
            }
            default -> { /* PASS/SHOT se zavrsavaju u pickupPass()/goalScored()/passFailed()/shotMissed() */ }
        }
    }

    private void recordActionResult(ActionOutcome outcome, Ball.BallState previousState,
                                    Ball.BallState expectedNewState, String duelWinnerId) {
        Action action = state.getAction();
        if (action == null || action.getActionId() == null) return;
        Ball.BallState newState = expectedNewState != null
                ? expectedNewState : state.getBall().getBallState();
        state.getEventStore().append(new ActionResultEvent(
                state.getSimulationTick(), state.getRound(), action.getActionId(), action.getType(),
                outcome, playerId(action.getActingPlayer()), playerId(action.getTargetPlayer()),
                action.getIntendedTarget(), action.getActualTarget(), action.getSkill(),
                previousState, newState, playerId(state.getCarrier()), duelWinnerId));
        if (previousState != newState) {
            state.getEventStore().append(new BallStateChangedEvent(
                    state.getSimulationTick(), state.getRound(), action.getActionId(),
                    previousState, newState, state.getBall().getPosition(),
                    playerId(state.getCarrier()), outcome.name()));
        }
    }

    private static String playerId(Player player) {
        return player == null ? null : player.getLabel();
    }

    private boolean isOwnGoalkeeperOrDefensiveRow(Player player, String team) {
        if ("GK".equals(player.getRole())) return true;
        return SimulationState.TEAM_HOME.equals(team)
                ? player.getPosition().getRow() <= 1.0
                : player.getPosition().getRow() >= 7.0;
    }
}
