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

    public SimulationEngine(List<Player> players, Ball ball, TacticsRules tacticsRules) {
        this(players, ball, tacticsRules, new Random());
    }

    public SimulationEngine(List<Player> players, Ball ball, TacticsRules tacticsRules, Random random) {
        this.state = new SimulationState(players, ball, tacticsRules, random);
        this.movementEngine = new MovementEngine(state);
        this.ballMovementEngine = new BallMovementEngine(state);
        this.playerSelectionEngine = new PlayerSelectionEngine(state);
        this.actionEngine = new ActionEngine(state, playerSelectionEngine);
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
            return; // zamrznuto tokom proslave gola
        }

        Action action = state.getAction();

        // PASS u toku — lopta leti ka primaocu.
        if (action != null && action.isPassInFlight()) {
            ballMovementEngine.moveBallTowardCurrentTarget();
            if (MovementEngine.distance(state.getBall().getPosition(),
                                        action.getTargetPlayer().getPosition()) < BallMovementEngine.PICKUP_DISTANCE) {
                actionEngine.pickupPass();
            } else {
                movementEngine.moveAllTowardTargets();
            }
            return;
        }

        // SHOT u toku — lopta leti ka golu.
        if (action != null && action.isShotInFlight()) {
            ballMovementEngine.moveBallTowardCurrentTarget();
            if (MovementEngine.distance(state.getBall().getPosition(),
                                        ActionEngine.GOAL_POSITION) < BallMovementEngine.PICKUP_DISTANCE) {
                actionEngine.goalScored();
                return;
            }
            movementEngine.moveAllTowardTargets();
            return;
        }

        // Nosilac juri loptu — preuzima je kad je dovoljno blizu.
        if (state.getCarrier() != null && state.getBall().getCarrier() != state.getCarrier()) {
            if (MovementEngine.distance(state.getCarrier().getPosition(),
                                        state.getBall().getPosition()) < BallMovementEngine.PICKUP_DISTANCE) {
                state.getBall().setCarrier(state.getCarrier());
                state.getCarrier().setTarget(null);
                state.setStatus(state.getCarrier().getLabel() + " has the ball");
            }
        }

        movementEngine.moveAllTowardTargets();

        ballMovementEngine.followCarrier();

        actionEngine.checkActionCompletion();
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
