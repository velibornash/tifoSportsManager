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

    /** Distance at which a player is considered to have reached a loose ball. */
    public static final double POSSESSION_RADIUS = BallMovementEngine.PICKUP_DISTANCE;
    /** Safety net: max ticks before a CHASE is force-resolved (~30s at 20 ticks/s). */
    public static final int CHASE_MAX_TICKS = 600;
    /** Consecutive ticks without meaningful progress before forced resolution (~2s). */
    public static final int CHASE_NO_PROGRESS_TICKS = 40;
    public static final double CHASE_PROGRESS_EPSILON = MovementEngine.PLAYER_SPEED * 0.25;

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
        if (type == Action.Type.CHASE) {
            state.incrementChaseCount();
            Position ballPos = state.getBall().getPosition();
            state.log("CHASE START: " + description
                    + " | ball=" + formatPosition(ballPos)
                    + " | chaser=" + formatChasePlayer(action.getActingPlayer(), ballPos));
        } else {
            state.log("Action started: " + description);
        }
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
     * U protivnickoj polovini pokušava THRU pas u prostor ispred trkača.
     */
    public void executePass() {
        Player carrier = state.getCarrier();

        // THRU pas: kad je nosilac u protivnickoj polovini, prosledi u prostor
        if (!"GK".equals(carrier.getRole()) && isInOpponentHalf(carrier)) {
            Player runner = findThruRunner(carrier);
            if (runner != null && state.getRandom().nextDouble() < 0.40) {
                executeThruPass(runner);
                return;
            }
        }

        int candidateCount = "GK".equals(carrier.getRole()) ? 2 : 6;
        List<Player> nearest = selection.nearestTeamTo(carrier, candidateCount);
        if (nearest.isEmpty()) {
            executeClearance();
            return;
        }

        // Filter za finalne redove: samo napred ili isti red
        double carrierRow = carrier.getPosition().getRow();
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        boolean inFinalRows = home ? (carrierRow >= 6) : (carrierRow <= 2);

        // Filter za kickoff: primaoc mora biti unazad (u sopstvenoj polovini)
        boolean isKickoff = carrierRow == 4 && carrier.getPosition().getColumn() == 3.5;

        List<Player> eligibleReceivers = new java.util.ArrayList<>();
        for (Player candidate : nearest) {
            if (isOwnGoalkeeperOrDefensiveRow(candidate, carrier.getTeam())) {
                continue;
            }
            if (inFinalRows) {
                double candidateRow = candidate.getPosition().getRow();
                // HOME redovi 6-7: samo napred (row >= carrierRow) ili isti red
                // AWAY redovi 1-2: samo napred (row <= carrierRow) ili isti red
                boolean validRow = home ? (candidateRow >= carrierRow) : (candidateRow <= carrierRow);
                if (!validRow) continue;
            }
            if (isKickoff) {
                double candidateRow = candidate.getPosition().getRow();
                // Kickoff: primaoc mora biti unazad (u sopstvenoj polovini)
                // HOME: row < 4, AWAY: row > 4
                boolean validRow = home ? (candidateRow < 4) : (candidateRow > 4);
                if (!validRow) continue;
            }
            eligibleReceivers.add(candidate);
        }

        if (eligibleReceivers.isEmpty()) {
            executeClearance();
            return;
        }

        Player receiver = eligibleReceivers.get(state.getRandom().nextInt(eligibleReceivers.size()));
        executePassTo(receiver);
    }

    /** Da li je nosilac u protivnickoj polovini. */
    private boolean isInOpponentHalf(Player carrier) {
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        return home ? carrier.getPosition().getRow() >= 4
                    : carrier.getPosition().getRow() <= 4;
    }

    /**
     * Traži saigrača koji je NAPRED od nosioca (bliže golu) i može primiti THRU pas.
     * Ne vraća golmana niti igrače u zabranjenim redovima.
     */
    private Player findThruRunner(Player carrier) {
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        List<Player> nearest = selection.nearestTeamTo(carrier, 6);
        for (Player p : nearest) {
            if (p == carrier) continue;
            if ("GK".equals(p.getRole())) continue;
            boolean ahead = home
                    ? p.getPosition().getRow() > carrier.getPosition().getRow()
                    : p.getPosition().getRow() < carrier.getPosition().getRow();
            if (!ahead) continue;
            // Zabranjeni redovi — sopstvena odbrambena zona (rovn 1 za HOME, red 7 za AWAY)
            if (home && p.getPosition().getRow() <= 1) continue;
            if (!home && p.getPosition().getRow() >= 7) continue;
            return p;
        }
        return null;
    }

    /**
     * THRU pas: lopta ide u prostor ISPRED trkača, ne na njegovu trenutnu poziciju.
     * Target = pozicija trkača + vektor napred (1–2 celije ka golu).
     */
    public void executeThruPass(Player runner) {
        Player carrier = state.getCarrier();
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        double forwardRow = home ? 1.0 : -1.0;

        // Meta = prostor ispred trkača (1–2 celije napred ka golu)
        double ahead = 1.0 + state.getRandom().nextDouble();
        double targetRow = MovementEngine.clamp(
                runner.getPosition().getRow() + forwardRow * ahead, 1, 7);
        double colJitter = (state.getRandom().nextDouble() * 2 - 1) * 0.5;
        double targetCol = MovementEngine.clamp(
                runner.getPosition().getColumn() + colJitter, 1, 6);
        Position thruTarget = new Position(targetRow, targetCol);

        runner.setLocked(true);
        state.incrementPassAttempts(carrier.getTeam());

        Action.PassHeight passHeight = choosePassHeight(carrier.getPosition(), thruTarget, runner);
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), thruTarget, runner,
                Action.PassLength.THRU, passHeight);
        boolean actualOutside = isOutsidePitch(result.actualTarget());
        boolean received = result.received() && !actualOutside;
        Position flightTarget = received ? thruTarget : outOfBoundsEndpoint(result.actualTarget());

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);

        String qualityLabel = received ? "GOOD" : "POOR";
        String description = "THRU: " + carrier.getLabel() + " -> " + runner.getLabel()
                + " | " + passHeight
                + " | passing: " + result.skill() + "/20 | " + qualityLabel
                + " | target: " + formatPosition(flightTarget);
        start(Action.Type.PASS, description);

        Action action = state.getAction();
        action.setTargetPlayer(runner);
        action.setTargetPosition(thruTarget);
        action.setExecutionOrigin(carrier.getPosition());
        action.setSkill(result.skill());
        action.setIntendedTarget(thruTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);
        action.setPassLength(Action.PassLength.THRU);
        action.setPassHeight(passHeight);
        state.incrementActionCount();

        // AIR pas — provera deflection-a na startu
        if (passHeight == Action.PassHeight.AIR) {
            checkAirPassDeflectionAtStart(action);
        }
    }

    public void executePassTo(Player receiver) {
        if (isOwnGoalkeeperOrDefensiveRow(receiver, state.getCarrier().getTeam())) {
            executeClearance();
            return;
        }
        receiver.setLocked(true);
        state.incrementPassAttempts(state.getCarrier().getTeam());

        Position intendedTarget = receiver.getPosition();
        Action.PassLength passLength = choosePassLength(state.getCarrier().getPosition(), intendedTarget);
        Action.PassHeight passHeight = choosePassHeight(state.getCarrier().getPosition(), intendedTarget, receiver);
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                state.getCarrier(), state.getCarrier().getPosition(), intendedTarget, receiver, passLength, passHeight);
        boolean actualOutside = isOutsidePitch(result.actualTarget());
        boolean received = result.received() && !actualOutside;
        Position flightTarget = received ? intendedTarget : outOfBoundsEndpoint(result.actualTarget());

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);

        String qualityLabel = received ? "GOOD" : "POOR";
        String description = "PASS: " + state.getCarrier().getLabel() + " -> " + receiver.getLabel()
                + " | " + passLength + " " + passHeight
                + " | passing: " + result.skill() + "/20 | " + qualityLabel
                + " | target: " + formatPosition(flightTarget);
        start(Action.Type.PASS, description);

        Action action = state.getAction();
        action.setTargetPlayer(receiver);
        action.setTargetPosition(intendedTarget);
        action.setExecutionOrigin(state.getCarrier().getPosition());
        action.setSkill(result.skill());
        action.setIntendedTarget(intendedTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);
        action.setPassLength(passLength);
        action.setPassHeight(passHeight);
        state.incrementActionCount();

        // AIR pass deflection check at start: if opponent very close to passer
        if (passHeight == Action.PassHeight.AIR) {
            checkAirPassDeflectionAtStart(action);
        }
    }

    /** Check if AIR pass gets deflected at start by nearby opponent. */
    private void checkAirPassDeflectionAtStart(Action action) {
        Player passer = action.getActingPlayer();
        Player closestOpponent = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(passer.getTeam())) continue;
            double dist = MovementEngine.distance(p.getPosition(), passer.getPosition());
            if (dist < minDist) {
                minDist = dist;
                closestOpponent = p;
            }
        }
        if (closestOpponent != null && minDist <= 1.0) {
            // Deflection chance based on opponent's height, technique, defender
            PlayerSkills s = closestOpponent.getSkills();
            double deflectionPower = closestOpponent.heightSkill() * 0.40
                    + s.technique() * 0.30 + s.defender() * 0.30;
            double passerProtection = passer.heightSkill() * 0.20
                    + passer.getSkills().technique() * 0.30;
            if (deflectionPower + state.getRandom().nextDouble() * 5 > passerProtection + 10) {
                // Deflection: lopta odbija u random pravcu od pasera (0.5–1.5 celija)
                double angle = state.getRandom().nextDouble() * 2 * Math.PI;
                double dist = 0.5 + state.getRandom().nextDouble() * 1.0;
                Position deflected = new Position(
                        MovementEngine.clamp(passer.getPosition().getRow() + Math.sin(angle) * dist, 1, 7),
                        MovementEngine.clamp(passer.getPosition().getColumn() + Math.cos(angle) * dist, 1, 6));
                action.setActualTarget(deflected);
                state.getBall().setTarget(deflected);
                action.setGoodExecution(false);
                state.log("AIR PASS DEFLECTED at start by " + closestOpponent.getLabel()
                        + " -> " + formatPosition(deflected));
            }
        }
    }

    /** Choose pass length based on distance to target. */
    private Action.PassLength choosePassLength(Position from, Position to) {
        double dist = MovementEngine.distance(from, to);
        if (dist <= 5) return Action.PassLength.SHORT;
        if (dist <= 15) return Action.PassLength.LONG;
        return Action.PassLength.LONG; // very long treated as LONG
    }

    /** Choose pass height based on situation. */
    private Action.PassHeight choosePassHeight(Position from, Position to, Player receiver) {
        double dist = MovementEngine.distance(from, to);
        // Short passes usually ground; long passes can be air to avoid interception
        if (dist <= 8) return Action.PassHeight.GROUND;
        // Long passes: 50% air to avoid ground interception
        return state.getRandom().nextBoolean() ? Action.PassHeight.AIR : Action.PassHeight.GROUND;
    }

    /**
     * CROSS: igrac sa krila salje loptu u box.
     * Cilj je protivnicki box (red 5-6 za HOME, 2-3 za AWAY), sredina.
     */
    public void executeCross() {
        Player carrier = state.getCarrier();
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        // Target zone: central box area
        double targetRow = home ? 5.5 + state.getRandom().nextDouble() * 1.0
                               : 2.5 - state.getRandom().nextDouble() * 1.0;
        double targetCol = 2.5 + state.getRandom().nextDouble() * 3.0;
        Position intendedTarget = new Position(targetRow, targetCol);

        // Find best aerial candidate in the box
        List<Player> boxAttackers = selection.nearestTeamTo(carrier, 8);
        Player aerialTarget = null;
        double bestAerialScore = -1;
        for (Player p : boxAttackers) {
            if (p == carrier) continue;
            double pr = p.getPosition().getRow();
            boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
            if (!inBox) continue;
            double score = p.heightSkill() * 0.40 + p.getSkills().technique() * 0.30
                    + p.getSkills().striker() * 0.20;
            if (score > bestAerialScore) {
                bestAerialScore = score;
                aerialTarget = p;
            }
        }

        if (aerialTarget == null) {
            // No one in the box — fallback to regular pass
            executePass();
            return;
        }

        aerialTarget.setLocked(true);
        state.incrementPassAttempts(carrier.getTeam());

        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), intendedTarget, aerialTarget);
        boolean received = result.received() && !isOutsidePitch(result.actualTarget());
        Position flightTarget = received ? intendedTarget : outOfBoundsEndpoint(result.actualTarget());

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);

        String qualityLabel = received ? "GOOD" : "POOR";
        start(Action.Type.CROSS, "CROSS: " + carrier.getLabel() + " -> " + aerialTarget.getLabel()
                + " | passing: " + result.skill() + "/20 | " + qualityLabel
                + " | target: " + formatPosition(flightTarget));

        Action action = state.getAction();
        action.setTargetPlayer(aerialTarget);
        action.setTargetPosition(intendedTarget);
        action.setSkill(result.skill());
        action.setIntendedTarget(intendedTarget);
        action.setActualTarget(flightTarget);
        action.setGoodExecution(received);
        state.incrementActionCount();
    }

    /**
     * CENTER (centarsut): igrac iz poslednjeg treceg salje loptu u box
     * za AERIAL duel. Ide direktno ka najboljem napadacku u boxu.
     */
    public void executeCenter() {
        Player carrier = state.getCarrier();
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());

        List<Player> boxAttackers = selection.nearestTeamTo(carrier, 8);
        Player aerialTarget = null;
        double bestAerialScore = -1;
        for (Player p : boxAttackers) {
            if (p == carrier) continue;
            double pr = p.getPosition().getRow();
            boolean inBox = home ? (pr >= 5 && pr <= 7) : (pr >= 1 && pr <= 3);
            if (!inBox) continue;
            double score = p.heightSkill() * 0.40 + p.getSkills().technique() * 0.30
                    + p.getSkills().striker() * 0.20;
            if (score > bestAerialScore) {
                bestAerialScore = score;
                aerialTarget = p;
            }
        }

        if (aerialTarget == null) {
            executePass();
            return;
        }

        aerialTarget.setLocked(true);
        state.incrementPassAttempts(carrier.getTeam());

        Position intendedTarget = aerialTarget.getPosition();
        ExecutionQuality.PassResult result = executionQuality.evaluatePass(
                carrier, carrier.getPosition(), intendedTarget, aerialTarget);
        boolean received = result.received() && !isOutsidePitch(result.actualTarget());
        Position flightTarget = received ? intendedTarget : outOfBoundsEndpoint(result.actualTarget());

        state.getBall().setCarrier(null);
        state.getBall().setTarget(flightTarget);

        String qualityLabel = received ? "GOOD" : "POOR";
        start(Action.Type.CENTER, "CENTER: " + carrier.getLabel() + " -> " + aerialTarget.getLabel()
                + " | passing: " + result.skill() + "/20 | " + qualityLabel
                + " | target: " + formatPosition(flightTarget));

        Action action = state.getAction();
        action.setTargetPlayer(aerialTarget);
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
        boolean home = SimulationState.TEAM_HOME.equals(carrier.getTeam());
        int dr;

        // Zabrana unazad carry u finalnim redovima:
        // HOME redovi 6-7: samo napred (+1) ili lateral (0)
        // AWAY redovi 1-2: samo napred (-1) ili lateral (0)
        boolean inFinalRows = home ? (r >= 6) : (r <= 2);

        if (inFinalRows) {
            // U finalnim redovima: rerollovaj sve dok ne dobiješ napred ili lateral
            do { dr = weightedForwardDr(); } while (home ? dr < 0 : dr > 0);
        } else {
            // Ostali redovi: samo ne unazad (originalno pravilo)
            do { dr = weightedForwardDr(); } while (dr < 0);
        }

        int dc = state.getRandom().nextInt(3) - 1; // -1, 0, 1
        if (dr == 0 && dc == 0) {
            dr = 1;
        }
        double direction = home ? 1 : -1;
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

    /**
     * Deflection: lopta je dodirnuta ali nekontrolisano — postaje LOOSE
     * na zadatoj (odbijenoj) poziciji. Posed se ne menja.
     */
    public void deflectionLoose(Player deflector, Position deflectedPos) {
        Action action = state.getAction();
        Ball.BallState previousState = state.getBall().getBallState();
        if (action.getTargetPlayer() != null) {
            action.getTargetPlayer().setLocked(false);
        }
        state.getBall().setPosition(deflectedPos);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);
        state.setStatus("DEFLECTION — LOOSE BALL");
        state.log("DEFLECTION by " + deflector.getLabel()
                + " on " + action.getActingPlayer().getLabel() + "'s pass");
        recordActionResult(ActionOutcome.PASS_LOOSE, previousState, null, null);
        complete("DEFLECTION by " + deflector.getLabel() + " -> LOOSE BALL");
    }

    /** Primaoc hvata loptu — PASS se zavrsava, nosilac postaje primaoc. */
    public void pickupPass() {
        Action action = state.getAction();
        Player receiver = action.getTargetPlayer();
        // Ako primalac nije blizu lopte, lopta postaje loose
        if (MovementEngine.distance(receiver.getPosition(), state.getBall().getPosition())
                > BallMovementEngine.PICKUP_DISTANCE) {
            passFailed();
            return;
        }
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
                    case CROSS -> ActionOutcome.CROSS_DUEL_LOST;
                    case CENTER -> ActionOutcome.CENTER_DUEL_LOST;
                    case AERIAL -> ActionOutcome.AERIAL_LOST;
                };
        if (action != null && action.getType() == Action.Type.CHASE) {
            state.incrementChaseResolutionCount();
            state.log("CHASE RESOLUTION: " + winner.getLabel() + " | " + reason);
        }
        recordActionResult(outcome, previousState, null, winner.getLabel());
        complete("DUEL: " + winner.getLabel() + " wins | " + reason);
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
        // Ball goes to GK's ACTUAL position (not goal center)
        Position gkPos = goalkeeper.getPosition();
        state.getBall().setPosition(gkPos);
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
                + " | position: " + formatPosition(gkPos)
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
        state.setCelebratingTeam(scorer.getTeam());
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
     * Logs one CHASE animation tick for deadlock diagnosis.
     */
    public void logChaseTick(Action action) {
        Position ballPos = state.getBall().getPosition();
        Player chaser = action.getActingPlayer();
        Player rival = closestOpposingActiveChaser(chaser, ballPos);
        double dChaser = chaser == null ? Double.NaN
                : MovementEngine.distance(chaser.getPosition(), ballPos);
        double dRival = rival == null ? Double.NaN
                : MovementEngine.distance(rival.getPosition(), ballPos);
        state.log("CHASE TICK: time=" + state.matchClockLabel()
                + " ball=" + formatPosition(ballPos)
                + " chaser=" + formatChasePlayer(chaser, ballPos)
                + " rival=" + (rival == null ? "none" : formatChasePlayer(rival, ballPos))
                + " dChaser=" + formatDistance(dChaser)
                + " dRival=" + formatDistance(dRival)
                + " elapsed=" + action.getChaseTicks()
                + " noProgress=" + action.getChaseNoProgressTicks()
                + " state=CHASE");
    }

    /**
     * Force-resolves a CHASE that exceeded the hard tick limit.
     */
    public void resolveChaseTimeout() {
        Action action = state.getAction();
        if (action == null || action.getType() != Action.Type.CHASE) return;
        state.incrementChaseTimeoutCount();
        Player winner = selection.closestEligibleToBall(state.getBall().getPosition());
        state.log("CHASE TIMEOUT after " + action.getChaseTicks() + " ticks"
                + " | winner=" + (winner == null ? "none" : winner.getLabel()));
        if (winner != null) {
            completeChasePossession(winner, "CHASE TIMEOUT");
        } else {
            complete("CHASE TIMEOUT — no eligible player");
        }
    }

    /**
     * Force-resolves a CHASE stuck with zero meaningful progress.
     */
    public void resolveChaseNoProgress() {
        Action action = state.getAction();
        if (action == null || action.getType() != Action.Type.CHASE) return;
        state.log("CHASE NO PROGRESS after " + action.getChaseNoProgressTicks()
                + " ticks | elapsed=" + action.getChaseTicks());
        Player winner = selection.closestEligibleToBall(state.getBall().getPosition());
        if (winner != null) {
            completeChasePossession(winner, "CHASE NO PROGRESS");
        } else {
            complete("CHASE NO PROGRESS — no eligible player");
        }
    }

    private void completeChasePossession(Player winner, String reason) {
        state.incrementChaseResolutionCount();
        Ball.BallState previousState = state.getBall().getBallState();
        state.getBall().setTarget(null);
        state.getBall().setCarrier(winner);
        state.setCarrier(winner);
        winner.setTarget(null);
        state.setActionDelayTicks(SimulationState.ACTION_PAUSE_TICKS);
        recordActionResult(ActionOutcome.CHASE_POSSESSION, previousState, null, null);
        state.log("CHASE RESOLUTION: " + winner.getLabel() + " | " + reason);
        // Move winner to exact ball position
        winner.setPosition(state.getBall().getPosition());
        complete("CHASE: " + winner.getLabel() + " has the ball | " + reason);
    }

    private Player closestOpposingActiveChaser(Player chaser, Position ballPos) {
        if (chaser == null) return null;
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player candidate : state.getActiveChasers()) {
            if (candidate == chaser || candidate.getTeam().equals(chaser.getTeam())) continue;
            if (candidate.isLocked() || state.isBlockedAfterDuel(candidate)) continue;
            double distance = MovementEngine.distance(candidate.getPosition(), ballPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private boolean hasOpposingChaseContest(Position ballPos) {
        boolean homeNear = false;
        boolean awayNear = false;
        for (Player chaser : state.getActiveChasers()) {
            if (chaser.isLocked() || state.isBlockedAfterDuel(chaser)) continue;
            if (MovementEngine.distance(chaser.getPosition(), ballPos) > POSSESSION_RADIUS) continue;
            if (SimulationState.TEAM_HOME.equals(chaser.getTeam())) homeNear = true;
            else awayNear = true;
        }
        return homeNear && awayNear;
    }

    private static String formatChasePlayer(Player player, Position ballPos) {
        if (player == null) return "none";
        Position pos = player.getPosition();
        return player.getLabel() + " pos=" + formatPosition(pos)
                + " dBall=" + formatDistance(MovementEngine.distance(pos, ballPos));
    }

    private static String formatDistance(double distance) {
        if (Double.isNaN(distance)) return "n/a";
        return String.format(Locale.ROOT, "%.3f", distance);
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
                Position ballPos = state.getBall().getPosition();
                if (hasOpposingChaseContest(ballPos)) {
                    return;
                }
                Player winner = selection.closestEligibleActiveChaser(ballPos);
                // CHASE pickup zahteva tačnu lokaciju lopte (ne samo radius)
                if (winner != null
                        && MovementEngine.distance(winner.getPosition(), ballPos) <= 0.01) {
                    completeChasePossession(winner, "at exact ball position");
                }
            }
            case CARRY -> {
                Player carrier = state.getCarrier();
                boolean targetReached = carrier.getTarget() == null
                        || (carrier.getTarget() != null
                            && MovementEngine.distance(carrier.getPosition(), carrier.getTarget()) < MovementEngine.PLAYER_SPEED * 2);
                if (targetReached && state.getBall().getCarrier() == carrier) {
                    carrier.setTarget(null);
                    state.setActionDelayTicks(SimulationState.ACTION_PAUSE_TICKS);
                    recordActionResult(ActionOutcome.CARRY_COMPLETED,
                            Ball.BallState.IN_POSSESSION, null, null);
                    complete("CARRY: " + carrier.getLabel());
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
