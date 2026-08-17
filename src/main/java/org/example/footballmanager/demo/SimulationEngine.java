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
 *  - Krecu se SAMO igraci HOME ekipe; AWAY igraci se NIKAD ne pomeraju.
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
    private final DuelResolver duelResolver;
    private int blockedCarryTicks;

    public SimulationEngine(List<Player> players, Ball ball, TacticsRules tacticsRules) {
        this(players, ball, tacticsRules, new Random());
    }

    public SimulationEngine(List<Player> players, Ball ball, TacticsRules tacticsRules, Random random) {
        this.state = new SimulationState(players, ball, tacticsRules, random);
        this.movementEngine = new MovementEngine(state);
        this.ballMovementEngine = new BallMovementEngine(state);
        this.playerSelectionEngine = new PlayerSelectionEngine(state);
        this.duelEngine = new DuelEngine(state);
        this.duelResolver = new DuelResolver(random);
        this.actionEngine = new ActionEngine(state, playerSelectionEngine, new ExecutionQuality(random));
        this.tacticalIntentEngine = new TacticalIntentEngine(state);
        this.stepEngine = new SimulationStepEngine(state, playerSelectionEngine, actionEngine, tacticalIntentEngine);
    }

    // --- zivotni ciklus ---

    /**
     * Jedna RUNDA: donosi odluku (ili ceka ako je pas/sut u letu) i dodeljuje
     * ciljeve igracima (max 1 celija). Glatko kretanje radi {@link #advance()}.
     */
    public String step() {
        return stepEngine.step();
    }

    /**
     * Jedan TICK animacije (poziva ga Timer svakih ~16ms):
     * pomera igrace ka ciljevima, lopta leti ka cilju (pas/sut),
     * primaoc preuzima loptu, gol se resetuje.
     */
    public void advance() {
        if (state.isCelebrating()) {
            // During celebration: move home players toward goal area (row 8, cols 1-3)
            // in a slow celebration pattern, no teleport
            if (state.getGoalCount() > 0 && state.getAction() == null) {
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
            duelEngine.update(action);
            // Lopta je stigla do svoje STVARNE mete (ne primaoca)
            if (MovementEngine.distance(state.getBall().getPosition(),
                                        action.getActualTarget()) < BallMovementEngine.PICKUP_DISTANCE) {
                if (isOutsidePitch(action.getActualTarget())) {
                    actionEngine.passOutOfBounds();
                    scheduleAwayRestart(action.getActualTarget());
                } else if (action.isGoodExecution()) {
                    DuelResult duelResult = resolveDuel(action);
                    if (duelResult != null && duelResult.outcome() == DuelOutcome.DEFENDER_WINS) {
                        actionEngine.giveBallTo(duelResult.winner(), "RECEIVE_PASS defender");
                    } else {
                        actionEngine.pickupPass();
                    }
                } else {
                    actionEngine.passFailed();
                }
            }
            duelEngine.update(state.getAction());
            return;
        }

        // SHOT u toku — lopta leti ka odstupnoj meti. Igraci reaguju na poziciju lopte.
        if (action != null && action.isShotInFlight()) {
            ballMovementEngine.moveBallTowardCurrentTarget();
            tacticalIntentEngine.refreshTargetsIfBallStateChanged();
            movementEngine.moveAllTowardTargets();
            duelEngine.update(action);
            // Lopta je stigla do svoje STVARNE mete (ne gola)
            if (MovementEngine.distance(state.getBall().getPosition(),
                                        action.getActualTarget()) < BallMovementEngine.PICKUP_DISTANCE) {
                if (action.isGoodExecution()) {
                    DuelResult duelResult = resolveDuel(action);
                    if (duelResult != null && duelResult.outcome() == DuelOutcome.DEFENDER_WINS) {
                        actionEngine.shotSaved(duelResult.winner());
                    } else {
                        actionEngine.goalScored();
                    }
                } else {
                    actionEngine.shotMissed();
                    scheduleAwayRestart(state.getBall().getPosition());
                }
            }
            duelEngine.update(state.getAction());
            return;
        }

        if (state.getReturningPlayer() != null
                && (state.getReturningPlayer().getTarget() == null
                || MovementEngine.distance(state.getReturningPlayer().getPosition(),
                state.getReturningPlayer().getTarget()) <= 1e-9)) {
            state.setReturningPlayer(null);
        }

        // CHASE: player moves toward ball. Ball NEVER moves.
        // Pickup only happens in step() when player reaches exact ball position.
        Player actionPlayer = action != null ? action.getActingPlayer() : null;
        Position beforeMove = actionPlayer != null ? actionPlayer.getPosition() : null;
        Position carryTarget = action != null && action.getType() == Action.Type.CARRY
                && actionPlayer != null ? actionPlayer.getTarget() : null;
        double carryDistanceBefore = carryTarget == null || actionPlayer == null
                ? Double.NaN : MovementEngine.distance(beforeMove, carryTarget);
        movementEngine.moveAllTowardTargets();
        duelEngine.update(action);

        if (action != null && (action.getType() == Action.Type.CHASE
                || action.getType() == Action.Type.CARRY)) {
            DuelResult duelResult = duelEngine.resolveActiveDuel(duelResolver);
            if (duelResult != null && duelResult.outcome() == DuelOutcome.DEFENDER_WINS) {
                actionEngine.giveBallTo(duelResult.winner(), action.getType().name());
                duelEngine.update(state.getAction());
                return;
            }
        }

        // Carrier ne ostaje zaglavljen: ako ne moze ni minimalno da se
        // pomeri oko prepreke, odmah bira pas umesto da prekine akciju.
        if (action != null && action.getType() == Action.Type.CARRY
                && actionPlayer != null && carryTarget != null
                && actionPlayer.getTarget() != null) {
            double carryDistanceAfter = MovementEngine.distance(actionPlayer.getPosition(), carryTarget);
            if (carryDistanceAfter >= carryDistanceBefore - 1e-6) {
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

        // Ako je Chase igrac potpuno okruzen, menjamo jurioca. Akcija se ne
        // zavrsava kao neuspeh i lopta ostaje potpuno nepomicna dok neko ne
        // stigne tacno na njenu koordinatu.
        if (action != null && action.getType() == Action.Type.CHASE
                && actionPlayer != null && beforeMove != null
                && actionPlayer.getTarget() != null
                && MovementEngine.distance(beforeMove, actionPlayer.getPosition()) <= 1e-12) {
            Player replacement = playerSelectionEngine.closestHomeTo(
                    state.getBall().getPosition(), actionPlayer);
            if (replacement != null) {
                actionPlayer.setTarget(null);
                state.setCarrier(replacement);
                replacement.setTarget(state.getBall().getPosition());
                actionEngine.start(Action.Type.CHASE, replacement.getLabel() + " chasing ball");
                return;
            }
        }

        ballMovementEngine.followCarrier();
        tacticalIntentEngine.refreshTargetsIfBallStateChanged();

        actionEngine.checkActionCompletion();
        duelEngine.update(state.getAction());
    }

    private DuelResult resolveDuel(Action action) {
        duelEngine.update(action);
        return duelEngine.resolveActiveDuel(duelResolver);
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
        state.getBall().setCarrier(null);
        away.setTarget(restartPosition);
        state.setAwayRestartPending(true);
        state.setRestartPassToHomeGoalkeeper(passToHomeGoalkeeper);
        actionEngine.start(Action.Type.CHASE, away.getLabel() + " moving to restart");
    }

    private Position restartPosition(Position outPosition) {
        // Bocna linija je izmedju celija: leva ivica celije 1 = 0.5,
        // desna ivica celije 6 = 6.5. To nije centar celije 1/6.
        double col = outPosition.getColumn() <= 0 ? 0.5
                : outPosition.getColumn() >= 7 ? 6.5 : outPosition.getColumn();
        return new Position(outPosition.getRow(), col);
    }

    private void scheduleAwayRestart(Position outPosition) {
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
            passToHomeGoalkeeper = true;
        } else {
            restartPlayer = playerSelectionEngine.closestAwayTo(outPosition);
            restartPosition = restartPosition(outPosition);
            passToHomeGoalkeeper = true;
        }
        state.setPendingRestartPlayer(restartPlayer);
        state.setPendingRestartPosition(restartPosition);
        state.setRestartPassToHomeGoalkeeper(passToHomeGoalkeeper);
        state.setRestartHoldTicks(60);
        state.setRoundComplete(false);
    }

    /**
     * Pomera domace igrace ka cilju gola tokom proslave (red 8, kolone 1-3).
     * Igraci se pomeraju glatko bez teleportacije, kao animacija proslave.
     * Prosledjuje se i kretanje oko svoje osovine.
     */
    private void movePlayersDuringGoalCelebration() {
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
            double celebSpeed = 0.12; // oko 2.4 celije u sekundi pri timeru od 50ms
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

    /** Pomera domace igrace ka cilju gola tokom proslave (red 8, kolone 1-3).
     * Igraci se pomeraju glatko bez teleportacije, kao animacija proslave.
     * Prosledjuje se i kretanje oko svoje osovine.
     */
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

    // --- javni API (nepromenjen) ---

    public String getStatus() {
        return state.getStatus();
    }

    public int getGoalCount() {
        return state.getGoalCount();
    }

    public int getActionCount() {
        return state.getActionCount();
    }

    public int getShotCount() {
        return state.getShotCount();
    }

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
}
