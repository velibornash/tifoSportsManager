package org.example.footballmanager.demo;

import java.util.List;
import java.util.Random;

/**
 * Minimalna fudbalska simulacija za demo (prvi simulacioni demo).
 *
 * SimulationEngine je glavni ORKESTRATOR: drzi komponente (stanje, korak,
 * akcije, kretanje, lopta, taktika, izbor igraca) i koordinira zivotni ciklus:
 *
 * <pre>
 *   step()      → {@link SimulationStepEngine} (odluka / jedna runda)
 *   advance()   → tick animacije (komponente: lopta, kretanje, akcije)
 *   reset()     → {@link SimulationState}
 * </pre>
 *
 * Pravila ove faze (svesno pojednostavljena — bez golmana, duela, skillsa,
 * offsajda, scoringa):
 *
 *  - HOME i AWAY igraci se krecu prema istim tactical-editor pravilima,
 *    uz perspektivno preslikavanje za AWAY.
 *  - U jednoj rundi (jedan {@link #step()}) igrac dobija cilj udaljen
 *    najvise 1 celiju (8 smerova); glatko kretanje ka cilju radi
 *    {@link #advance()} na svakom tick-u animacije.
 *  - Nosilac lopte = najblizi HOME igrac (ako nema nosioca). Dok nema
 *    loptu, nosilac juri loptu.
 *  - Nosilac u redovima 1-5: nasumicno PASS ili CARRY.
 *  - Nosilac u redovima 6-7: nasumicno PASS, CARRY ili SHOT.
 *  - CARRY = 1 celija (blagi nagib napred), lopta prati nosioca.
 *  - PASS = 6 najblizih HOME igraca, nasumicni primaoc; primaoc je LOCKED
 *    u toj rundi; lopta leti bez ogranicenja distance ka primaocu.
 *  - SHOT = lopta leti ka away golu (8, 3.5); po golu RESET na pocetno stanje.
 *  - Igraci bez lopte jure ka desired celiji iz {@link TacticsRules}
 *    (1 celija po rundi).
 *
 * Javni API je NEPROMENJEN u odnosu na pre refaktora — renderer i testovi
 * komuniciraju iskljucivo kroz njega.
 */
public class SimulationEngine {

    private final SimulationState state;
    private final SimulationStepEngine stepEngine;
    private final ActionEngine actionEngine;
    private final MovementEngine movementEngine;
    private final BallMovementEngine ballMovementEngine;
    private final TacticalIntentEngine tacticalIntentEngine;
    private final PlayerSelectionEngine playerSelectionEngine;
    private final DuelEngine duelEngine;
    private final DuelResolutionCoordinator duelResolution;
    private final PlaymakingDecisionEngine playmakingEngine;
    private int blockedCarryTicks;

    public SimulationEngine(List<Player> players, Ball ball, TacticsRules tacticsRules) {
        this(players, ball, tacticsRules, new Random());
    }

    public SimulationEngine(List<Player> players, Ball ball, TacticsRules tacticsRules, Random random) {
        this(players, ball, tacticsRules, random, false);
    }

    /**
     * Constructor that may activate the {@link ThreatEngine} defensive/offside
     * safety layer (Threat A/B/C, offside-retreat, offside violation restart).
     * Existing tests and {@code DemoSimulationFactory.create} use the ctors above
     * (layer OFF). The live demo opts in via
     * {@link DemoSimulationFactory#createWithThreatOverride}.
     */
    public SimulationEngine(List<Player> players, Ball ball, TacticsRules tacticsRules, Random random,
                            boolean threatOverride) {
        this.state = new SimulationState(players, ball, tacticsRules, random);
        this.movementEngine = new MovementEngine(state);
        this.ballMovementEngine = new BallMovementEngine(state);
        this.playerSelectionEngine = new PlayerSelectionEngine(state);
        this.duelEngine = new DuelEngine(state);
        this.duelResolution = new DuelResolutionCoordinator(state, duelEngine, new DuelResolver(random));
        this.actionEngine = new ActionEngine(state, playerSelectionEngine, new ExecutionQuality(random));
        this.tacticalIntentEngine = threatOverride
                ? new TacticalIntentEngine(state, new ThreatEngine(state, playerSelectionEngine))
                : new TacticalIntentEngine(state);
        this.playmakingEngine = new PlaymakingDecisionEngine(state, playerSelectionEngine, random);
        this.stepEngine = new SimulationStepEngine(state, playerSelectionEngine, actionEngine, tacticalIntentEngine, playmakingEngine);
    }

    /**
     * Per-match toggle for the Type C (local-proximity) threat override (§4:
     * temporarily disabled). No-op when the threat layer is off
     * (the {@code threatOverride=false} ctors / {@code DemoSimulationFactory.create}).
     */
    public void setThreatTypeCEnabled(boolean enabled) {
        ThreatEngine te = tacticalIntentEngine.getThreatEngine();
        if (te != null) {
            te.setTypeCEnabled(enabled);
        }
    }

    // --- zivotni ciklus ---

    /**
     * Jedna RUNDA: donosi odluku (ili ceka ako je pas/sut u letu) i dodeljuje
     * ciljeve igracima (max 1 celija). Glatko kretanje radi {@link #advance()}.
     */
    public String step() {
        if (state.isHalfTime() || state.isMatchFinished()) {
            return state.getStatus();
        }
        return stepEngine.step();
    }

    /** Read-only access to recorded action/duel events for replay consumers. */
    public SimulationEventStore getEventStore() {
        return state.getEventStore();
    }

    /** Read-only access to recorded per-tick scene snapshots for replay consumers. */
    public SimulationSnapshotStore getSnapshotStore() {
        return state.getSnapshotStore();
    }

    /** Immutable aggregate containing all saved events and tick snapshots. */
    public SimulationRecording getRecording() {
        return state.getRecording();
    }

    /** Applies one persisted frame for UI replay; no simulation tick is run. */
    public void applySnapshot(SimulationSnapshot snapshot) {
        if (snapshot != null) state.applySnapshot(snapshot);
    }

    /**
     * Jedan TICK animacije (poziva ga Timer svakih ~16ms):
     * pomera igrace ka ciljevima, lopta leti ka cilju (pas/sut),
     * primaoc preuzima loptu, gol se resetuje.
     */
    public void advance() {
        state.advanceSimulationTick();
        try {
            advanceInternal();
        } finally {
            state.captureSnapshot();
        }
    }

    private void advanceInternal() {
        if (state.isHalfTime() || state.isMatchFinished()) {
            return;
        }
        state.advanceMatchClock();
        if (state.isHalfTime() || state.isMatchFinished()) {
            return;
        }
        state.consumeDuelCooldownTick();
        state.consumeDuelVisualTick();
        if (state.isCelebrating()) {
            // During celebration: move scoring team players toward their goal area
            // (HOME -> row 8, AWAY -> row 0) in a slow celebration pattern
            if (state.getGoalCount() + state.getAwayGoalCount() > 0 && state.getAction() == null) {
                movePlayersDuringGoalCelebration();
            }
            return;
        }

        Action action = state.getAction();
        if (action == null || action.getType() != Action.Type.CARRY) {
            blockedCarryTicks = 0;
        }

        if (action == null && state.getPendingRestartPosition() != null
                && state.getRestartHoldTicks() > 0) {
            state.consumeRestartHoldTick();
            return;
        }

        // Prvi frejm ostavlja loptu na stvarnoj aut-liniji. Tek u sledecem
        // tick-u vracamo je na liniju celije i pokrecemo AWAY izvodjaca.
        if (action == null && state.getPendingRestartPosition() != null
                && state.getRestartHoldTicks() == 0) {
            Position restartPosition = state.getPendingRestartPosition();
            Player restartPlayer = state.getPendingRestartPlayer();
            boolean passToHomeGoalkeeper = state.isRestartPassToHomeGoalkeeper();
            state.setPendingRestartPosition(null);
            state.setPendingRestartPlayer(null);
            state.setRestartPassToHomeGoalkeeper(false);
            state.getBall().setPosition(restartPosition);
            startRestart(restartPosition, restartPlayer, passToHomeGoalkeeper);
            return;
        }

        if (action == null && state.isPendingCorner()) {
            if (state.getCornerHoldTicks() > 0) {
                state.consumeCornerHoldTick();
                return;
            }
            boolean right = state.isPendingCornerRight();
            int cornerRow = SimulationState.TEAM_HOME.equals(state.getCornerTeam()) ? 7 : 1;
            Position corner = new Position(cornerRow, right ? 6 : 1);
            Player taker = state.getCornerTaker();
            state.setPendingCorner(false);
            state.setPendingRestartPosition(corner);
            state.setPendingRestartPlayer(taker);
            state.getBall().setPosition(corner);
            state.log("CORNER ready: ball at " + formatPosition(corner)
                    + " | taker: " + (taker == null ? "none" : taker.getLabel()));
            return;
        }

        if (action == null && state.getActionDelayTicks() > 0) {
            state.consumeActionDelayTick();
            if (state.getActionDelayTicks() == 0) state.setRoundComplete(true);
            return;
        }

        // PASS u toku — lopta leti ka odstupnoj meti. Igraci reaguju na poziciju lopte.
        if (action != null && action.isPassInFlight()) {
            ballMovementEngine.moveBallTowardCurrentTarget();
            tacticalIntentEngine.refreshTargetsIfBallStateChanged();
            movementEngine.moveAllTowardTargets();

            // GROUND pass interception check (each tick). Clearance se ne proverava.
            if (action.getPassHeight() == Action.PassHeight.GROUND && !action.isClearance()) {
                Player interceptor = checkGroundPassInterception(action);
                if (interceptor != null) {
                    handleInterception(action, interceptor);
                    return;
                }
                // Deflection može da završi akciju unutar checkGroundPassInterception
                if (state.getAction() == null) return;
            }

            // Lopta je stigla do svoje STVARNE mete (ne primaoca)
            if (MovementEngine.distance(state.getBall().getPosition(),
                                        action.getActualTarget()) <= 0.01) {
                duelEngine.update(action);
                if (action.isClearance()) {
                    actionEngine.finishAwayClearance();
                } else if (isOutsidePitch(action.getActualTarget())) {
                    String lastTouchTeam = action.getActingPlayer().getTeam();
                    actionEngine.passOutOfBounds();
                    scheduleRestart(action.getActualTarget(), lastTouchTeam);
                } else if (action.isGoodExecution()) {
                    DuelResult duelResult = duelResolution.resolve(action);
                    if (duelResult != null && duelResult.outcome() == DuelOutcome.DEFENDER_WINS) {
                        actionEngine.giveBallTo(duelResult.winner(), "RECEIVE_PASS defender");
                    } else {
                        actionEngine.pickupPass();
                    }
                } else {
                    actionEngine.passFailed();
                }
            }
            return;
        }

        // CROSS/CENTER u toku — isti mehanizam kao PASS
        if (action != null && action.isCrossInFlight()) {
            ballMovementEngine.moveBallTowardCurrentTarget();
            tacticalIntentEngine.refreshTargetsIfBallStateChanged();
            movementEngine.moveAllTowardTargets();
            if (MovementEngine.distance(state.getBall().getPosition(),
                                        action.getActualTarget()) <= 0.01) {
                duelEngine.update(action);
                if (isOutsidePitch(action.getActualTarget())) {
                    String lastTouchTeam = action.getActingPlayer().getTeam();
                    actionEngine.passOutOfBounds();
                    scheduleRestart(action.getActualTarget(), lastTouchTeam);
                } else if (action.isGoodExecution()) {
                    DuelResult duelResult = duelResolution.resolve(action);
                    if (duelResult != null && duelResult.outcome() == DuelOutcome.DEFENDER_WINS) {
                        actionEngine.giveBallTo(duelResult.winner(), "AERIAL defender");
                    } else {
                        // Attacker wins aerial — becomes carrier in the box
                        actionEngine.pickupPass();
                    }
                } else {
                    actionEngine.passFailed();
                }
            }
            return;
        }

        // SHOT u toku — lopta leti ka odstupnoj meti. Igraci reaguju na poziciju lopte.
        if (action != null && action.isShotInFlight()) {
            ballMovementEngine.moveBallTowardCurrentTarget();
            tacticalIntentEngine.refreshTargetsIfBallStateChanged();
            movementEngine.moveAllTowardTargets();
            // SAVE ima sopstvenu glatku putanju posle kontakta sa golmanom.
            if (action.getSaveType() != Action.SaveType.NONE
                    && MovementEngine.distance(state.getBall().getPosition(), action.getActualTarget()) <= 0.01) {
                if (action.getSaveType() == Action.SaveType.CORNER_REBOUND) {
                    boolean right = action.getActualTarget().getColumn() > 3.5;
                    String attackingTeam = action.getActingPlayer().getTeam();
                    actionEngine.finishCornerRebound();
                    state.setPendingCorner(true);
                    state.setPendingCornerRight(right);
                    state.setCornerTeam(attackingTeam);
                    state.setCornerHoldTicks(SimulationState.SET_PIECE_HOLD_TICKS);
                    Player taker = playerSelectionEngine.teamByRole(attackingTeam, right ? "MR" : "ML");
                    state.setCornerTaker(taker);
                    if (taker != null) {
                        applyCornerTacticalTargets(right, attackingTeam);
                    }
                    state.log("CORNER sequence started: " + (right ? "right" : "left")
                            + " | hold 3.00s");
                } else {
                    actionEngine.finishFieldRebound();
                }
                duelEngine.update(state.getAction());
                return;
            }

            // The goal line is a calculation boundary only. A good shot flies
            // directly to row 8; resolve the GK duel when that visual path
            // crosses row 7, without redirecting the ball to (7, 3.5).
            Position logicalGoal = action.getLogicalGoalPosition();
            boolean crossedGoalLine = SimulationState.TEAM_HOME.equals(action.getActingPlayer().getTeam())
                    ? state.getBall().getPosition().getRow() >= logicalGoal.getRow()
                    : state.getBall().getPosition().getRow() <= logicalGoal.getRow();
            if (action.isGoodExecution() && !action.isGoalLineResolved() && crossedGoalLine) {
                duelEngine.update(action);
                DuelResult duelResult = duelResolution.resolve(action);
                if (duelResult != null && duelResult.outcome() == DuelOutcome.DEFENDER_WINS) {
                    actionEngine.shotSaved(duelResult.winner());
                } else {
                    actionEngine.continueGoalAfterGkDuel();
                }
            }

            if (state.hasActiveAction() && action.isGoalLineResolved()
                    && MovementEngine.distance(state.getBall().getPosition(),
                                               action.getActualTarget()) <= 0.01) {
                actionEngine.goalScored();
            } else if (state.hasActiveAction() && !action.isGoodExecution()
                    && MovementEngine.distance(state.getBall().getPosition(),
                                               action.getActualTarget()) <= 0.01) {
                actionEngine.shotMissed();
                scheduleRestart(state.getBall().getPosition(), action.getActingPlayer().getTeam());
            }
            return;
        }

        if (state.getReturningPlayer() != null
                && (state.getReturningPlayer().getTarget() == null
                || MovementEngine.distance(state.getReturningPlayer().getPosition(),
                state.getReturningPlayer().getTarget()) <= 0.01)) {
            state.setReturningPlayer(null);
        }

        // CHASE: player moves toward ball. Ball NEVER moves.
        // Pickup uses possession radius — not exact coordinate equality.
        Action chaseAction = action != null && action.getType() == Action.Type.CHASE ? action : null;
        if (chaseAction != null) {
            Position ballPos = state.getBall().getPosition();
            Player leadChaser = chaseAction.getActingPlayer();
            double leadDistance = leadChaser == null ? Double.MAX_VALUE
                    : MovementEngine.distance(leadChaser.getPosition(), ballPos);
            chaseAction.recordChaseTick(leadDistance, ActionEngine.CHASE_PROGRESS_EPSILON);
            actionEngine.logChaseTick(chaseAction);
            if (chaseAction.getChaseTicks() >= ActionEngine.CHASE_MAX_TICKS) {
                actionEngine.resolveChaseTimeout();
                duelEngine.update(state.getAction());
                return;
            }
            if (chaseAction.getChaseNoProgressTicks() >= ActionEngine.CHASE_NO_PROGRESS_TICKS) {
                actionEngine.resolveChaseNoProgress();
                duelEngine.update(state.getAction());
                return;
            }
        }

        Player actionPlayer = action != null ? action.getActingPlayer() : null;
        Position beforeMove = actionPlayer != null ? actionPlayer.getPosition() : null;
        Position carryTarget = action != null && action.getType() == Action.Type.CARRY
                && actionPlayer != null ? actionPlayer.getTarget() : null;
        double carryDistanceBefore = carryTarget == null || actionPlayer == null
                ? Double.NaN : MovementEngine.distance(beforeMove, carryTarget);
        movementEngine.moveAllTowardTargets();
        duelEngine.update(action);

        if (chaseAction != null && actionPlayer != null) {
            Position ballPos = state.getBall().getPosition();
            Player closestChaser = playerSelectionEngine.closestEligibleActiveChaser(ballPos);
            if (closestChaser != null && closestChaser != actionPlayer
                    && chaseAction.getChaseNoProgressTicks() >= 10
                    && MovementEngine.distance(closestChaser.getPosition(), ballPos) + 0.05
                    < MovementEngine.distance(actionPlayer.getPosition(), ballPos)) {
                actionEngine.completeBlockedChase();
                duelEngine.update(state.getAction());
                return;
            }
        }

        if (action != null && (action.getType() == Action.Type.CHASE
                || action.getType() == Action.Type.CARRY)) {
            DuelResult duelResult = duelResolution.resolve(action);
            if (duelResult != null) {
                if (action.getType() == Action.Type.CHASE
                        || duelResult.outcome() == DuelOutcome.DEFENDER_WINS) {
                    actionEngine.giveBallTo(duelResult.winner(), action.getType().name());
                    duelEngine.update(state.getAction());
                    return;
                }
                // Dribbler attacker wins: continue carry, but close this duel.
                actionEngine.prepareDribbleBypass(duelEngine.getActiveDuel().getDefender());
                duelEngine.closeAfterResolution();
            }
        }

        // Carrier ne ostaje zaglavljen: ako ne moze ni minimalno da se
        // pomeri oko prepreke, odmah bira pas umesto da prekine akciju.
        if (action != null && action.getType() == Action.Type.CARRY
                && actionPlayer != null && carryTarget != null
                && actionPlayer.getTarget() != null) {
            double carryDistanceAfter = MovementEngine.distance(actionPlayer.getPosition(), carryTarget);
            // Collision avoidance may produce a tiny side movement while the
            // carrier is still effectively blocked. Count that as stuck too;
            // otherwise two HOME players can keep pushing/oscillating forever.
            double carryProgress = carryDistanceBefore - carryDistanceAfter;
            if (carryProgress < MovementEngine.PLAYER_SPEED * 0.25) {
                blockedCarryTicks++;
            } else {
                blockedCarryTicks = 0;
            }
            if (blockedCarryTicks >= 3) {
                blockedCarryTicks = 0;
                actionEngine.executePass();
                return;
            }
        }

        ballMovementEngine.followCarrier();
        tacticalIntentEngine.refreshTargetsIfBallStateChanged();

        actionEngine.checkActionCompletion();
        duelEngine.update(state.getAction());
    }

    /**
     * Proverava da li neki odbrambeni igrač može da presretne GROUND pas.
     *
     * Pravila (drugTi design review):
     *  - Svaki odbrambeni se evaluira TAČNO JEDNOM po pasu, u tick-u kada
     *    lopta prolazi pored njegove projekcije na preostalu putanju
     *    (s_p ∈ [0, BALL_SPEED]). Nema ponovljenog bacanja kocke.
     *  - Geometrija: projekcija igrača na [ballPos, actualTarget].
     *    Δ = t_player − t_ball ≤ 0 (igrač stiže pre lopte).
     *  - Skill u 3 stupnja: READ (12), CONTACT (13), CONTROL (12).
     *    Pad CONTROL-a → DEFLECTION (loose lopta sa random skretanjem).
     */
    private Player checkGroundPassInterception(Action action) {
        Position ballPos = state.getBall().getPosition();
        Position target = action.getActualTarget();
        Position origin = action.getExecutionOrigin() != null
                ? action.getExecutionOrigin() : action.getActingPlayer().getPosition();

        // Pass line vector (origin → target)
        double dx = target.getColumn() - origin.getColumn();
        double dy = target.getRow() - origin.getRow();
        double passLength = Math.hypot(dx, dy);
        if (passLength < 1e-9) return null;

        // Ball progress along full pass line (0..1) — filter ends
        double ballDx = ballPos.getColumn() - origin.getColumn();
        double ballDy = ballPos.getRow() - origin.getRow();
        double ballProgress = (ballDx * dx + ballDy * dy) / (passLength * passLength);
        if (ballProgress < 0.1 || ballProgress > 0.9) return null;

        // Remaining trajectory: ballPos → target
        double remDx = target.getColumn() - ballPos.getColumn();
        double remDy = target.getRow() - ballPos.getRow();
        double remLength = Math.hypot(remDx, remDy);
        if (remLength < 1e-9) return null;
        double remUx = remDx / remLength;   // unit vector along remaining line
        double remUy = remDy / remLength;

        String attackingTeam = action.getActingPlayer().getTeam();
        String defendingTeam = SimulationState.TEAM_HOME.equals(attackingTeam)
                ? "AWAY" : SimulationState.TEAM_HOME;

        Player bestInterceptor = null;
        double bestDelta = Double.MAX_VALUE;
        Player deflectionCandidate = null;
        double deflectionDelta = Double.MAX_VALUE;
        Position deflectionPos = null;

        for (Player p : state.getPlayers()) {
            if (!p.getTeam().equals(defendingTeam)) continue;
            if (state.isBlockedAfterDuel(p)) continue;
            if (p == action.getTargetPlayer()) continue;

            // Projekcija igrača na preostalu putanju, relativno od ballPos
            double pdx = p.getPosition().getColumn() - ballPos.getColumn();
            double pdy = p.getPosition().getRow() - ballPos.getRow();
            double s_p = pdx * remUx + pdy * remUy;

            // Evaluiraj samo kada lopta prolazi pored projekcije (jedan tick prozor)
            if (s_p < 0 || s_p > BallMovementEngine.BALL_SPEED) continue;

            // Tacka presretanja = projekcija na preostalu liniju
            double interceptRow = ballPos.getRow() + remUy * s_p;
            double interceptCol = ballPos.getColumn() + remUx * s_p;
            Position interceptPos = new Position(interceptRow, interceptCol);

            double distToIntercept = MovementEngine.distance(p.getPosition(), interceptPos);
            double playerTime = distToIntercept
                    / (MovementEngine.PLAYER_SPEED * (p.getSkills().pace() / 20.0));
            double ballTime = s_p / BallMovementEngine.BALL_SPEED;
            double delta = playerTime - ballTime;

            if (delta > 0) continue;   // ne stiže pre lopte

            // READ: PLAYMAKING×0.60 + DEFENDER×0.40, random 0..5, threshold 12
            PlayerSkills s = p.getSkills();
            double readSkill = s.playmaking() * 0.60 + s.defender() * 0.40;
            if (readSkill + state.getRandom().nextDouble() * 5 <= 12) continue;

            // CONTACT: DEFENDER×0.55 + TECHNIQUE×0.30 + PACE×0.15, random 0..4, threshold 13
            double contactSkill = s.defender() * 0.55 + s.technique() * 0.30 + s.pace() * 0.15;
            if (contactSkill + state.getRandom().nextDouble() * 4 <= 13) continue;

            // CONTROL: TECHNIQUE×0.50 + DEFENDER×0.30 + PLAYMAKING×0.20, random 0..4, threshold 12
            double controlSkill = s.technique() * 0.50 + s.defender() * 0.30 + s.playmaking() * 0.20;
            if (controlSkill + state.getRandom().nextDouble() * 4 <= 12) {
                // CONTROL pao → DEFLECTION
                if (delta < deflectionDelta) {
                    deflectionDelta = delta;
                    deflectionCandidate = p;
                    deflectionPos = interceptPos;
                }
                continue;
            }

            // Sva 3 prošla → INTERCEPTION kandidat
            if (delta < bestDelta) {
                bestDelta = delta;
                bestInterceptor = p;
            }
        }

        if (bestInterceptor != null) return bestInterceptor;

        if (deflectionCandidate != null) {
            // Deflection: lopta odbija u random pravcu od tacke dodira
            double angle = state.getRandom().nextDouble() * 2 * Math.PI;
            double dist = 0.3 + state.getRandom().nextDouble() * 0.5;   // 0.3–0.8 celije
            Position deflected = new Position(
                    MovementEngine.clamp(deflectionPos.getRow() + Math.sin(angle) * dist, 1, 7),
                    MovementEngine.clamp(deflectionPos.getColumn() + Math.cos(angle) * dist, 1, 6));
            actionEngine.deflectionLoose(deflectionCandidate, deflected);
        }
        return null;
    }

    /** Handle successful interception. */
    private void handleInterception(Action action, Player interceptor) {
        state.getBall().setTarget(null);
        state.getBall().setCarrier(interceptor);
        state.setCarrier(interceptor);
        interceptor.setTarget(null);
        interceptor.setLocked(false);
        if (action.getTargetPlayer() != null) {
            action.getTargetPlayer().setLocked(false);
        }
        state.log("INTERCEPTION by " + interceptor.getLabel() + " on " + action.getActingPlayer().getLabel() + "'s pass");
        actionEngine.giveBallTo(interceptor, "INTERCEPTION");
    }

    private boolean isOutsidePitch(Position position) {
        return position.getRow() < 1 || position.getRow() > 7
                || position.getColumn() < 1 || position.getColumn() > 6;
    }

    private void startRestart(Position ballPosition, Player restartPlayer,
                              boolean passToHomeGoalkeeper) {
        Player away = restartPlayer;
        if (away == null) return;
        Position restartPosition = restartPosition(ballPosition);
        state.setRoundComplete(false);
        state.setCarrier(away);
        state.getBall().setCarrier(away);
        away.setTarget(restartPosition);
        state.setAwayRestartPending(true);
        state.setRestartPassToHomeGoalkeeper(passToHomeGoalkeeper);
        state.setActiveChasers(away, null);
        actionEngine.start(Action.Type.CHASE, away.getLabel() + " moving to restart");
    }

    private Position restartPosition(Position outPosition) {
        // Bocna linija je izmedju celija: leva ivica celije 1 = 0.5,
        // desna ivica celije 6 = 6.5. To nije centar celije 1/6.
        double col = outPosition.getColumn() <= 0 ? 0.5
                : outPosition.getColumn() >= 7 ? 6.5 : outPosition.getColumn();
        return new Position(outPosition.getRow(), col);
    }

    private String formatPosition(Position p) {
        return String.format(java.util.Locale.ROOT, "(%.2f,%.2f)", p.getRow(), p.getColumn());
    }

    private void scheduleRestart(Position outPosition, String lastTouchTeam) {
        boolean homeGoalKick = outPosition.getRow() <= 0;
        boolean awayGoalKick = outPosition.getRow() >= 8;
        Player restartPlayer;
        Position restartPosition;
        boolean passToHomeGoalkeeper;

        if (homeGoalKick) {
            restartPlayer = playerSelectionEngine.closestHomeGoalkeeper();
            restartPosition = restartPlayer.getPosition();
            passToHomeGoalkeeper = false;
        } else if (awayGoalKick) {
            restartPlayer = playerSelectionEngine.closestAwayGoalkeeper();
            restartPosition = restartPlayer.getPosition();
            passToHomeGoalkeeper = false;
        } else {
            String restartingTeam = SimulationState.TEAM_HOME.equals(lastTouchTeam)
                    ? "AWAY" : SimulationState.TEAM_HOME;
            restartPlayer = playerSelectionEngine.closestTeamTo(outPosition, restartingTeam);
            restartPosition = restartPosition(outPosition);
            passToHomeGoalkeeper = false;
        }
        state.setPendingRestartPlayer(restartPlayer);
        state.setPendingRestartPosition(restartPosition);
        state.setRestartPassToHomeGoalkeeper(passToHomeGoalkeeper);
        state.setRestartHoldTicks(60);
        state.setRoundComplete(false);
    }

    private void applyCornerTacticalTargets(boolean physicalRight, String attackingTeam) {
        boolean editorRight = SimulationState.TEAM_HOME.equals(attackingTeam)
                ? physicalRight : !physicalRight;
        String side = editorRight ? "RIGHT" : "LEFT";
        for (Player player : state.getPlayers()) {
            String context = player.getTeam().equals(attackingTeam)
                    ? "ATTACK_" + side : "DEFEND_" + side;
            Position desired = state.getTacticsRules().cornerCell(
                    player.getRole(), context, player.getTeam());
            state.setTacticalDesiredPosition(player, desired);
            if (player != state.getCornerTaker() && !player.isLocked()) {
                player.setTarget(MovementEngine.oneCellToward(player.getPosition(), desired));
            }
        }
    }

    /**
     * Pomera igrace koji su postigli gol ka cilju gola tokom proslave.
     * HOME gol: domaci igraci na red 8, kolone 1-3.
     * AWAY gol: gostujuci igraci na red 0, kolone 1-3.
     * Igraci se pomeraju glatko bez teleportacije, kao animacija proslave.
     * Prosledjuje se i kretanje oko svoje osovine.
     */
    private void movePlayersDuringGoalCelebration() {
        String celebratingTeam = state.getCelebratingTeam();
        if (celebratingTeam == null) celebratingTeam = SimulationState.TEAM_HOME;
        double targetRow = SimulationState.TEAM_HOME.equals(celebratingTeam) ? 8.0 : 0.0;
        for (Player p : state.getPlayers()) {
            if (!celebratingTeam.equals(p.getTeam())) {
                continue;
            }
            if (p.isLocked()) {
                continue;
            }
            // Cilj: celije 8_1 do 8_3 za HOME gol, odnosno 0_1 do 0_3 za AWAY gol
            double sourceCol = p.getAlternativePosition().getColumn();
            double targetCol = sourceCol <= 2.33 ? 1.0
                    : sourceCol <= 4.33 ? 2.0 : 3.0;
            Position currentPos = p.getPosition();
            // Glatko kretanje ka cilju za manji speed
            double dx = targetCol - currentPos.getColumn();
            double dy = targetRow - currentPos.getRow();
            double dist = Math.hypot(dx, dy);
            double celebSpeed = 0.12; // oko 2.4 celije u sekundi pri timeru od 50ms
            if (dist > celebSpeed) {
                p.setPosition(new Position(
                        currentPos.getRow() + dy / dist * celebSpeed,
                        currentPos.getColumn() + dx / dist * celebSpeed));
            }
            // Ako je igrac gotovo na cilju, pomeri ga malo oko sebe
            if (dist <= celebSpeed) {
                // Mini-proslava: glatko kretanje oko pozicije
                double orbitAngle = System.currentTimeMillis() / 100.0
                        + (p.getId().hashCode() & 0xFF) * 0.08;
                double orbitRadius = 0.28;
                p.setPosition(new Position(
                        targetRow + Math.sin(orbitAngle) * orbitRadius,
                        targetCol + Math.cos(orbitAngle) * orbitRadius));
            }
        }
    }

    /** Pomera igrace koji su postigli gol ka cilju gola tokom proslave.
     *  (HOME -> red 8, AWAY -> red 0, kolone 1-3). */
    private void movePlayersDuringGoalCelebration_old() {
        for (Player p : state.getPlayers()) {
            if (!SimulationState.TEAM_HOME.equals(p.getTeam())) {
                continue;
            }
            if (p.isLocked()) {
                continue;
            }
            // Cilj: celije 8_1 do 8_3 (koordinate red 8, kolone 1-3)
            double targetCol = 2.0; // sredina izmedju 1 i 3
            double targetRow = 8.0;
            Position currentPos = p.getPosition();
            // Glatko kretanje ka cilju za manji speed
            double dx = targetCol - currentPos.getColumn();
            double dy = targetRow - currentPos.getRow();
            double dist = Math.hypot(dx, dy);
            double celebSpeed = 0.01; // sporza kretanje tokom proslave
            if (dist > celebSpeed) {
                p.setPosition(new Position(
                        currentPos.getRow() + dy / dist * celebSpeed,
                        currentPos.getColumn() + dx / dist * celebSpeed));
            }
            // Ako je igrac gotovo na cilju, pomeri ga malo oko sebe
            if (dist <= celebSpeed) {
                // Mini-proslava: glatko kretanje oko pozicije
                double orbitAngle = System.currentTimeMillis() / 100.0 % (2 * Math.PI);
                double orbitRadius = 0.1;
                p.setPosition(new Position(
                        targetRow + Math.sin(orbitAngle) * orbitRadius,
                        targetCol + Math.cos(orbitAngle) * orbitRadius));
            }
        }
    }

    /** Reset na pocetno stanje (nakon gola ili klikom na "Reset State"). */
    public void reset() {
        state.reset();
    }

    public void resetMatch() {
        state.resetMatch();
    }

    // --- javni API (nepromenjen) ---

    public String getStatus() {
        return state.getStatus();
    }

    public int getGoalCount() {
        return state.getGoalCount();
    }

    public int getAwayGoalCount() { return state.getAwayGoalCount(); }

    public int getActionCount() {
        return state.getActionCount();
    }

    public int getShotCount() {
        return state.getShotCount();
    }

    public String getMatchClockLabel() { return state.matchClockLabel(); }
    public boolean isHalfTime() { return state.isHalfTime(); }
    public boolean isMatchFinished() { return state.isMatchFinished(); }
    public boolean isMatchStarted() { return state.isMatchStarted(); }
    public List<GoalRecord> getGoals() { return state.getGoals(); }
    public int getPassAttempts() { return state.getPassAttempts(); }
    public int getPassCompletions() { return state.getPassCompletions(); }
    public int getShotsOnTarget() { return state.getShotsOnTarget(); }
    public int getAwayPassAttempts() { return state.getAwayPassAttempts(); }
    public int getAwayPassCompletions() { return state.getAwayPassCompletions(); }
    public int getAwayShotsOnTarget() { return state.getAwayShotsOnTarget(); }
    public int getChaseCount() { return state.getChaseCount(); }
    public int getChaseResolutionCount() { return state.getChaseResolutionCount(); }
    public int getChaseTimeoutCount() { return state.getChaseTimeoutCount(); }

    public void startSecondHalf() { state.startSecondHalf(); }
    public void startMatchSimulation() { state.startMatchSimulation(); }
    public void pauseSimulation() { state.pauseSimulation(); }
    public void resumeSimulation() { state.resumeSimulation(); }
    public boolean isSimulationRunning() { return state.isSimulationRunning(); }
    public void setDiagLogging(boolean enabled) { state.setDiagLogging(enabled); }

    public int getRound() {
        return state.getRound();
    }

    /** Pozicija igraca na POCETKU tekuceg/poslednjeg turna. */
    public Position getRoundStartPosition(Player p) {
        return state.getRoundStartPosition(p);
    }

    /** Desired pozicija (cilj) igraca dodeljena u tekucem/poslednjem turnu. */
    public Position getDesiredPosition(Player p) {
        return state.getDesiredPosition(p);
    }

    /** Pozicija igraca na KRAJU turna (osvezena kad se akcija zavrsi). */
    public Position getRoundEndPosition(Player p) {
        return state.getRoundEndPosition(p);
    }

    /**
     * Puna takticka desired celija iz editora (pravilo za (role, pozicija
     * lopte)) — NIJE 1-cell korak kretanja, vec konacni cilj.
     */
    public Position getTacticalDesiredPosition(Player p) {
        return state.getTacticalDesiredPosition(p);
    }

    /** Pozicija LOPTE na pocetku tekuceg/poslednjeg turna. */
    public Position getRoundStartBallPosition() {
        return state.getRoundStartBallPosition();
    }

    /** Pozicija LOPTE na kraju tekuceg/poslednjeg turna. */
    public Position getRoundEndBallPosition() {
        return state.getRoundEndBallPosition();
    }

    /** Pozicija LOPTE kojom su RACUNATA takticka pravila u poslednjem turnu. */
    public Position getTacticalBallPosition() {
        return state.getTacticalBallPosition();
    }

    /** Da li je tekuci turn zavrsen (kraj pozicija je finalan). */
    public boolean isRoundComplete() {
        return state.isRoundComplete();
    }

    /**
     * Koliko celija je igrac presao u tekucem/poslednjem turnu.
     * Meri se Chebyshev rastojanjem (max |dr|, |dc|) — dijagonala je 1,
     * pa je maksimum u BILO kom smeru 1 celija.
     */
    public double getCellsMoved(Player p) {
        return state.getCellsMoved(p);
    }

    public Ball getBall() {
        return state.getBall();
    }

    /** Ista lista igraca koju engin menja (deljena sa rendererom). */
    public List<Player> getPlayers() {
        return state.getPlayers();
    }

    public Player getCarrier() {
        return state.getCarrier();
    }

    /** Da li je akcija pokrenuta i jos traje (npr. pas/sut u letu, CARRY u toku). */
    public boolean isActionInProgress() {
        return state.hasActiveAction();
    }

    /**
     * Trenutna akcija simulacije; null = nema aktivne akcije.
     * Eksplicitan {@link Action} model tekuceg toka (tip, izvodjac, meta).
     */
    public Action getCurrentAction() {
        return state.getAction();
    }

    /** Da li je gol postignut i ceka se reset (simulacija zamrznuta). */
    public boolean isCelebrating() {
        return state.isCelebrating();
    }

    /** Odvodi i vraca nove poruke za Action Log (prazni interni red). */
    public List<String> getAndDrainMessages() {
        return state.drainMessages();
    }

    public boolean isDuelVisualActive() { return state.isDuelVisualActive(); }
    public Player getDuelVisualAttacker() { return state.getDuelVisualAttacker(); }
    public Player getDuelVisualDefender() { return state.getDuelVisualDefender(); }
    public Position getDuelVisualPosition() { return state.getDuelVisualPosition(); }
    public DuelType getDuelVisualType() { return state.getDuelVisualType(); }
}
