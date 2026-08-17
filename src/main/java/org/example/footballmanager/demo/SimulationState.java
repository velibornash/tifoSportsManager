package org.example.footballmanager.demo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralno STANJE simulacije. {@link SimulationEngine} je orkestrator koji
 * koordinira komponente; SVA mutable simulaciona stanja zive ovde:
 *
 *  - identitet / konfiguracija: igraci, lopta, pravila, random, pocetne pozicije
 *  - tekuci tok: {@link Action}, nosilac lopte
 *  - brojaci: status, golovi, akcije, sutevi, runda, proslava gola
 *  - log poruke (Action Log)
 *  - pracenje po rundi: start/kraj pozicija igraca i lopte, desired ciljevi,
 *    takticke desired celije i pozicija lopte kojom su racunata pravila
 *
 * Kao i modeli ({@link Player}, {@link Ball}, {@link Position}), stanje je
 * podatkovno — logiku vode komponente koje ga citaju/menjaju.
 */
public class SimulationState {

    private static final int MAX_MESSAGES = 8;
    private static final DateTimeFormatter APP_LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    public static final int SIMULATION_TICKS_PER_SECOND = 20;
    public static final int ACTION_PAUSE_TICKS = 0;       // nema pauze između akcija
    public static final int DUEL_LOSS_TICKS = 60;          // 3 s
    public static final int SET_PIECE_HOLD_TICKS = 60;    // 3 s
    public static final int CORNER_TAKER_HOLD_TICKS = 40; // 2 s

    public static final String TEAM_HOME = "HOME";

    private final List<Player> players;
    private final List<Position> initialPositions;
    private final Ball ball;
    private final TacticsRules tacticsRules;
    private final Random random;
    private final SimulationEventStore eventStore = new SimulationEventStore();
    private final SimulationSnapshotStore snapshotStore = new SimulationSnapshotStore();
    private final ArrayDeque<String> messages = new ArrayDeque<>();

    private Player carrier;
    private Action action;

    private String status = "ready";
    private int goalCount;
    private int actionCount;
    private int shotCount;
    private int round;
    private long simulationTick;
    private long nextActionSequence = 1;
    private boolean celebrating;
    private boolean awayRestartPending;
    private Player returningPlayer;
    private Position pendingRestartPosition;
    private Player pendingRestartPlayer;
    private boolean restartPassToHomeGoalkeeper;
    private long actionDelayUntilMs;
    private long restartHoldUntilMs;
    private int actionDelayTicks;
    private int restartHoldTicks;
    private final Map<Player, Integer> duelCooldownTicks = new HashMap<>();
    private boolean pendingCorner;
    private boolean pendingCornerRight;
    private Player cornerTaker;
    private int cornerHoldTicks;
    private Player duelVisualAttacker;
    private Player duelVisualDefender;
    private Position duelVisualPosition;
    private DuelType duelVisualType;
    private int duelVisualTicks;

    // --- pozicije po rundi (za Player Log): start, desired cilj i kraj turna ---
    private final Map<Player, Position> roundStartPositions = new HashMap<>();
    private final Map<Player, Position> roundEndPositions = new HashMap<>();
    private final Map<Player, Position> desiredPositions = new HashMap<>();
    private final Map<Player, Position> tacticalDesiredPositions = new HashMap<>();
    private final Map<Player, Integer> roundPaceSkills = new HashMap<>();
    private Position roundStartBallPosition;
    private Position roundEndBallPosition;
    private Position tacticalBallPosition;
    private String lastTacticalBallStateKey;
    private boolean roundComplete = true;

    public SimulationState(List<Player> players, Ball ball, TacticsRules tacticsRules, Random random) {
        this.players = players;
        this.ball = ball;
        this.tacticsRules = tacticsRules;
        this.random = random;
        this.initialPositions = new ArrayList<>(players.size());
        this.roundStartBallPosition = ball.getPosition();
        this.roundEndBallPosition = ball.getPosition();
        this.tacticalBallPosition = ball.getPosition();
        this.lastTacticalBallStateKey = TacticsRules.ballStateKey(ball.getPosition());
        for (Player p : players) {
            initialPositions.add(p.getPosition());
            roundStartPositions.put(p, p.getPosition());
            roundEndPositions.put(p, p.getPosition());
            desiredPositions.put(p, p.getPosition());
            tacticalDesiredPositions.put(p, p.getPosition());
        }
        captureSnapshot();
    }

    // --- pristup radnim objektima ---

    /** Ista lista igraca koju simulacija menja (deljena sa rendererom). */
    public List<Player> getPlayers() {
        return players;
    }

    public Ball getBall() {
        return ball;
    }

    public TacticsRules getTacticsRules() {
        return tacticsRules;
    }

    public Random getRandom() {
        return random;
    }

    /** Timeline for replay/statistics consumers; UI messages remain separate. */
    public SimulationEventStore getEventStore() {
        return eventStore;
    }

    public SimulationSnapshotStore getSnapshotStore() {
        return snapshotStore;
    }

    /** Returns a stable read model for replay and future statistics exporters. */
    public SimulationRecording getRecording() {
        return new SimulationRecording(eventStore.snapshot(), snapshotStore.snapshot(), goalCount);
    }

    /** Captures the complete immutable scene without deriving it later from logs. */
    public void captureSnapshot() {
        Action currentAction = action;
        List<PlayerSnapshot> playerSnapshots = players.stream()
                .map(player -> new PlayerSnapshot(
                        player.getId(), player.getLabel(), player.getTeam(), player.getRole(),
                        player.getPosition(), player.getTarget(), player.isLocked(),
                        player.getVelX(), player.getVelY()))
                .toList();
        snapshotStore.append(new SimulationSnapshot(
                simulationTick, round, playerSnapshots,
                ball.getPosition(), ball.getTarget(), ball.getBallState(),
                carrier == null ? null : carrier.getId(),
                currentAction == null ? null : currentAction.getActionId(),
                currentAction == null ? null : currentAction.getType(),
                currentAction == null || currentAction.getActingPlayer() == null
                        ? null : currentAction.getActingPlayer().getId(),
                currentAction == null || currentAction.getTargetPlayer() == null
                        ? null : currentAction.getTargetPlayer().getId(),
                currentAction == null ? null : currentAction.getIntendedTarget(),
                currentAction == null ? null : currentAction.getActualTarget(),
                status, goalCount));
    }

    public long getSimulationTick() { return simulationTick; }
    public void advanceSimulationTick() { simulationTick++; }
    public String nextActionId() { return "A-" + nextActionSequence++; }

    // --- nosilac i akcija ---

    public Player getCarrier() {
        return carrier;
    }

    public void setCarrier(Player carrier) {
        this.carrier = carrier;
    }

    /** Trenutna akcija; null = nema aktivne akcije. */
    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public boolean hasActiveAction() {
        return action != null;
    }

    // --- brojaci / status ---

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getGoalCount() {
        return goalCount;
    }

    public void incrementGoalCount() {
        goalCount++;
    }

    public int getActionCount() {
        return actionCount;
    }

    public void incrementActionCount() {
        actionCount++;
    }

    public int getShotCount() {
        return shotCount;
    }

    public void incrementShotCount() {
        shotCount++;
    }

    public int getRound() {
        return round;
    }

    public void incrementRound() {
        round++;
    }

    public boolean isCelebrating() {
        return celebrating;
    }

    public void setCelebrating(boolean celebrating) {
        this.celebrating = celebrating;
    }

    public boolean isAwayRestartPending() { return awayRestartPending; }
    public void setAwayRestartPending(boolean pending) { awayRestartPending = pending; }
    public Player getReturningPlayer() { return returningPlayer; }
    public void setReturningPlayer(Player player) { returningPlayer = player; }
    public Position getPendingRestartPosition() { return pendingRestartPosition; }
    public void setPendingRestartPosition(Position position) { pendingRestartPosition = position; }
    public Player getPendingRestartPlayer() { return pendingRestartPlayer; }
    public void setPendingRestartPlayer(Player player) { pendingRestartPlayer = player; }
    public boolean isRestartPassToHomeGoalkeeper() { return restartPassToHomeGoalkeeper; }
    public void setRestartPassToHomeGoalkeeper(boolean value) { restartPassToHomeGoalkeeper = value; }
    public long getActionDelayUntilMs() { return actionDelayUntilMs; }
    public void setActionDelayUntilMs(long value) { actionDelayUntilMs = value; }
    public int getActionDelayTicks() { return actionDelayTicks; }
    public void setActionDelayTicks(int ticks) { actionDelayTicks = Math.max(0, ticks); }
    public void consumeActionDelayTick() { if (actionDelayTicks > 0) actionDelayTicks--; }
    public int getRestartHoldTicks() { return restartHoldTicks; }
    public void setRestartHoldTicks(int ticks) { restartHoldTicks = Math.max(0, ticks); }
    public void consumeRestartHoldTick() { if (restartHoldTicks > 0) restartHoldTicks--; }
    public void blockAfterDuel(Player player) { if (player != null) duelCooldownTicks.put(player, DUEL_LOSS_TICKS); }
    public boolean isBlockedAfterDuel(Player player) { return duelCooldownTicks.getOrDefault(player, 0) > 0; }
    public void consumeDuelCooldownTick() {
        duelCooldownTicks.replaceAll((player, ticks) -> Math.max(0, ticks - 1));
        duelCooldownTicks.entrySet().removeIf(entry -> entry.getValue() == 0);
    }
    public boolean isPendingCorner() { return pendingCorner; }
    public void setPendingCorner(boolean value) { pendingCorner = value; }
    public boolean isPendingCornerRight() { return pendingCornerRight; }
    public void setPendingCornerRight(boolean value) { pendingCornerRight = value; }
    public Player getCornerTaker() { return cornerTaker; }
    public void setCornerTaker(Player player) { cornerTaker = player; }
    public int getCornerHoldTicks() { return cornerHoldTicks; }
    public void setCornerHoldTicks(int ticks) { cornerHoldTicks = Math.max(0, ticks); }
    public void consumeCornerHoldTick() { if (cornerHoldTicks > 0) cornerHoldTicks--; }
    public void showDuelVisual(Duel duel) {
        duelVisualAttacker = duel.getAttacker();
        duelVisualDefender = duel.getDefender();
        duelVisualPosition = duel.getContestPosition();
        duelVisualType = duel.getType();
        duelVisualTicks = 10;
    }
    public boolean isDuelVisualActive() { return duelVisualTicks > 0 && duelVisualPosition != null; }
    public Player getDuelVisualAttacker() { return duelVisualAttacker; }
    public Player getDuelVisualDefender() { return duelVisualDefender; }
    public Position getDuelVisualPosition() { return duelVisualPosition; }
    public DuelType getDuelVisualType() { return duelVisualType; }
    public void consumeDuelVisualTick() { if (duelVisualTicks > 0) duelVisualTicks--; }
    public long getRestartHoldUntilMs() { return restartHoldUntilMs; }
    public void setRestartHoldUntilMs(long value) { restartHoldUntilMs = value; }

    // --- log poruke (Action Log) ---

    public void log(String message) {
        messages.addLast(message);
        String timestamp = LocalDateTime.now().format(APP_LOG_TIME_FORMAT);
        System.out.println("[AppLog] " + timestamp + " " + message);
        while (messages.size() > MAX_MESSAGES) {
            messages.removeFirst();
        }
    }

    /** Odvodi i vraca nove poruke (prazni interni red). */
    public List<String> drainMessages() {
        List<String> drained = new ArrayList<>(messages);
        messages.clear();
        return drained;
    }

    // --- pracenje pozicija po rundi ---

    /** Pozicija igraca na POCETKU tekuceg/poslednjeg turna. */
    public Position getRoundStartPosition(Player p) {
        return roundStartPositions.getOrDefault(p, p.getPosition());
    }

    /** Desired pozicija (cilj) igraca dodeljena u tekucem/poslednjem turnu. */
    public Position getDesiredPosition(Player p) {
        return desiredPositions.getOrDefault(p, p.getPosition());
    }

    /** Pozicija igraca na KRAJU turna (osvezena kad se akcija zavrsi). */
    public Position getRoundEndPosition(Player p) {
        return roundEndPositions.getOrDefault(p, p.getPosition());
    }

    public void setRoundEndPosition(Player p, Position pos) {
        roundEndPositions.put(p, pos);
    }

    /**
     * Puna takticka desired celija iz editora (pravilo za (role, pozicija
     * lopte)) — NIJE 1-cell korak kretanja, vec konacni cilj.
     */
    public Position getTacticalDesiredPosition(Player p) {
        return tacticalDesiredPositions.getOrDefault(p, p.getPosition());
    }

    public void setTacticalDesiredPosition(Player p, Position pos) {
        tacticalDesiredPositions.put(p, pos);
    }

    /** Pozicija LOPTE na pocetku tekuceg/poslednjeg turna. */
    public Position getRoundStartBallPosition() {
        return roundStartBallPosition;
    }

    public void setRoundStartBallPosition(Position pos) {
        roundStartBallPosition = pos;
    }

    /** Pozicija LOPTE na kraju tekuceg/poslednjeg turna. */
    public Position getRoundEndBallPosition() {
        return roundEndBallPosition;
    }

    public void setRoundEndBallPosition(Position pos) {
        roundEndBallPosition = pos;
    }

    /** Pozicija LOPTE kojom su RACUNATA takticka pravila u poslednjem turnu. */
    public Position getTacticalBallPosition() {
        return tacticalBallPosition;
    }

    public void setTacticalBallPosition(Position pos) {
        tacticalBallPosition = pos;
    }

    public String getLastTacticalBallStateKey() {
        return lastTacticalBallStateKey;
    }

    public void setLastTacticalBallStateKey(String key) {
        lastTacticalBallStateKey = key;
    }

    /** Da li je tekuci turn zavrsen (kraj pozicija je finalan). */
    public boolean isRoundComplete() {
        return roundComplete;
    }

    public void setRoundComplete(boolean roundComplete) {
        this.roundComplete = roundComplete;
    }

    public int getRoundPaceSkill(Player p) {
        return roundPaceSkills.getOrDefault(p, 20);
    }

    /** Snima pozicije na pocetku novog turna (kraj se osvezava kad turn zavrsi). */
    public void beginRound() {
        roundComplete = false;
        roundStartBallPosition = ball.getPosition();
        roundEndBallPosition = ball.getPosition();
        roundPaceSkills.clear();
        for (Player p : players) {
            Position pos = p.getPosition();
            roundStartPositions.put(p, pos);
            roundEndPositions.put(p, pos);
            roundPaceSkills.put(p, random.nextInt(20) + 1);
        }
    }

    /** Snima desired poziciju (cilj) svakog igraca za tekuci turn. */
    public void recordDesiredPositions() {
        for (Player p : players) {
            Position target = p.getTarget();
            desiredPositions.put(p, target != null ? target : p.getPosition());
        }
    }

    /**
     * Koliko celija je igrac presao u tekucem/poslednjem turnu.
     * Meri se Chebyshev rastojanjem (max |dr|, |dc|) — dijagonala je 1,
     * pa je maksimum u BILO kom smeru 1 celija.
     */
    public double getCellsMoved(Player p) {
        Position start = roundStartPositions.get(p);
        Position end = roundComplete ? roundEndPositions.get(p) : p.getPosition();
        if (start == null || end == null) {
            return 0;
        }
        return Math.max(Math.abs(end.getRow() - start.getRow()),
                        Math.abs(end.getColumn() - start.getColumn()));
    }

    /** Reset na pocetno stanje (nakon gola ili klikom na "Reset State"). */
    public void reset() {
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.setPosition(initialPositions.get(i));
            p.setTarget(null);
            p.setLocked(false);
            p.setVelX(0);
            p.setVelY(0);
        }
        ball.setPosition(ball.getInitialPosition());
        ball.setTarget(null);
        ball.setCarrier(null);
        carrier = null;
        action = null;
        celebrating = false;
        awayRestartPending = false;
        returningPlayer = null;
        pendingRestartPosition = null;
        pendingRestartPlayer = null;
        restartPassToHomeGoalkeeper = false;
        actionDelayUntilMs = 0;
        restartHoldUntilMs = 0;
        actionDelayTicks = 0;
        restartHoldTicks = 0;
        duelCooldownTicks.clear();
        pendingCorner = false;
        pendingCornerRight = false;
        cornerTaker = null;
        cornerHoldTicks = 0;
        duelVisualAttacker = null;
        duelVisualDefender = null;
        duelVisualPosition = null;
        duelVisualType = null;
        duelVisualTicks = 0;
        roundComplete = true;
        roundStartBallPosition = ball.getInitialPosition();
        roundEndBallPosition = ball.getInitialPosition();
        tacticalBallPosition = ball.getInitialPosition();
        lastTacticalBallStateKey = TacticsRules.ballStateKey(ball.getInitialPosition());
        roundPaceSkills.clear();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            roundStartPositions.put(p, p.getPosition());
            roundEndPositions.put(p, p.getPosition());
            desiredPositions.put(p, p.getPosition());
            tacticalDesiredPositions.put(p, p.getPosition());
            roundPaceSkills.put(p, 20);
        }
        if (status.startsWith("GOAL")) {
            status += " (reset)";
        } else {
            status = "reset";
        }
        captureSnapshot();
    }
}
