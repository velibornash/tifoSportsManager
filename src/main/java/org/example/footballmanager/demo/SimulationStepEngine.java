package org.example.footballmanager.demo;

/**
 * Odgovornost: ODLUKA / KORAK SIMULACIJE.
 *
 * Obradjuje JEDNU rundu (jedan {@link SimulationEngine#step() step()}) koristeci
 * POSTOJECA pravila:
 *  - ceka ako je pas/sut u letu ili ako nosilac jos hoda
 *  - CHASE kad nema nosioca ili je daleko od lopte
 *  - nasumican izbor PASS/CARRY/SHOT (ISTI mehanizam kao pre refaktora)
 *  - dodela taktickih ciljeva i snimanje desired pozicija
 *
 * Bez ikakvog "pametnijeg" odlucivanja — bez taktickog scoringa, skillsa i AI.
 * Odluka se NE menja; samo je iza ciste arhitektonske granice.
 */
public class SimulationStepEngine {

    private final SimulationState state;
    private final PlayerSelectionEngine selection;
    private final ActionEngine actions;
    private final TacticalIntentEngine tactics;

    public SimulationStepEngine(SimulationState state, PlayerSelectionEngine selection,
                                ActionEngine actions, TacticalIntentEngine tactics) {
        this.state = state;
        this.selection = selection;
        this.actions = actions;
        this.tactics = tactics;
    }

    public String step() {
        // Tokom proslave gola simulacija je zamrznuta — novi turn se ne pokrece.
        if (state.isCelebrating()) {
            return state.getStatus();
        }

        // Dok lopta ceka restart, nikakav novi HOME Chase ne sme da pocne.
        if (state.getPendingRestartPosition() != null) {
            state.setStatus("restart waiting");
            return state.getStatus();
        }

        if (state.getActionDelayTicks() > 0) {
            state.setRoundComplete(false);
            state.setStatus("carrier holding ball");
            return state.getStatus();
        }

        // Ako je akcija pokrenuta (pas/sut u letu, CARRY u toku) — cekamo da zavrsi.
        if (state.hasActiveAction()) {
            state.setStatus("action in progress: " + state.getAction().getType().name());
            return state.getStatus();
        }

        if (state.getCarrier() != null && state.getCarrier().getTarget() != null) {
            state.setStatus(state.getCarrier().getLabel() + " still moving");
            return state.getStatus();
        }

        state.beginRound();

        Player carrier = state.getCarrier();
        if (carrier == null) {
            // LOOSE BALL: both closest HOME and AWAY chase, others go tactical
            if (state.isKickoffPending()) {
                carrier = selection.closestTeamTo(state.getBall().getPosition(), state.getKickoffTeam());
                state.setKickoffPending(false);
                state.setActiveChasers(carrier, null);
                carrier.setTarget(state.getBall().getPosition());
                tactics.assignTargets();
            } else {
                Player closestHome = selection.closestTeamTo(state.getBall().getPosition(), "HOME");
                Player closestAway = selection.closestTeamTo(state.getBall().getPosition(), "AWAY");
                carrier = closestOf(closestHome, closestAway, state.getBall().getPosition());
                state.setActiveChasers(closestHome, closestAway);
                // Both chasers chase the ball; others get tactical targets
                if (closestHome != null) closestHome.setTarget(state.getBall().getPosition());
                if (closestAway != null) closestAway.setTarget(state.getBall().getPosition());
                tactics.assignTargets();
                // Log both chasers for visibility
                if (closestHome != null && closestAway != null) {
                    state.log("CHASE race: " + closestHome.getLabel()
                            + " vs " + closestAway.getLabel());
                }
            }
            if (carrier == null) carrier = selection.closestHomeTo(state.getBall().getPosition());
            if (MovementEngine.distance(carrier.getPosition(), state.getBall().getPosition()) > 1e-9) {
                state.setCarrier(carrier);
                state.setKickoffTeam(SimulationState.TEAM_HOME);
                if (carrier.getTarget() == null) {
                    carrier.setTarget(state.getBall().getPosition());
                }
                state.recordDesiredPositions();
                actions.start(Action.Type.CHASE, carrier.getLabel() + " chasing ball");
                state.incrementRound();
                return state.getStatus();
            }
            // Preuzimanje je dozvoljeno samo na TACNOJ koordinati lopte.
            state.getBall().setCarrier(carrier);
            state.setCarrier(carrier);
            state.setKickoffTeam(SimulationState.TEAM_HOME);
            state.clearActiveChasers();
        } else if (state.getBall().getCarrier() != carrier) {
            if (MovementEngine.distance(carrier.getPosition(), state.getBall().getPosition()) > 1e-9) {
                if (carrier.getTarget() == null) {
                    carrier.setTarget(state.getBall().getPosition());
                }
                // Refresh tactical targets for non-chasing players each CHASE round
                tactics.assignTargets();
                state.recordDesiredPositions();
                actions.start(Action.Type.CHASE, carrier.getLabel() + " moving to ball");
                state.incrementRound();
                return state.getStatus();
            }
            state.getBall().setCarrier(carrier);
            state.setCarrier(carrier);
            carrier.setTarget(null);
            state.clearActiveChasers();
        }

        double row = carrier.getPosition().getRow();
        if (state.isAwayRestartPending()) {
            state.setAwayRestartPending(false);
            boolean cornerRestart = state.getCornerTaker() == carrier;
            if (!SimulationState.TEAM_HOME.equals(carrier.getTeam())) {
                state.setReturningPlayer(carrier);
                carrier.setTarget(carrier.getAlternativePosition());
                if (cornerRestart) {
                    Player cornerReceiver = selection.closestTeamTo(
                            new Position(3.5, 3.5), carrier.getTeam(), carrier);
                    state.setCornerTaker(null);
                    if (cornerReceiver != null) {
                        actions.executePassTo(cornerReceiver);
                    } else {
                        actions.executePassTo(selection.closestHomeGoalkeeper());
                    }
                } else actions.executePass();
            } else {
                if (cornerRestart) state.setCornerTaker(null);
                actions.executePass();
            }
            state.incrementRound();
            return state.getStatus();
        }
        boolean goalkeeper = "GK".equals(carrier.getRole());
        boolean canShoot = !goalkeeper && (SimulationState.TEAM_HOME.equals(carrier.getTeam())
                ? row >= ActionEngine.SHOOT_MIN_ROW : row <= 3);
        boolean inFinalThird = SimulationState.TEAM_HOME.equals(carrier.getTeam())
                ? row >= 6 : row <= 2;
        boolean onWing = carrier.getPosition().getColumn() <= 2
                || carrier.getPosition().getColumn() >= 5;
        boolean inOpponentHalf = SimulationState.TEAM_HOME.equals(carrier.getTeam())
                ? row >= 4 : row <= 4;
        String[] options;
        if (goalkeeper) {
            options = new String[] {"PASS", "CLEAR"};
        } else if (inFinalThird && onWing && inOpponentHalf) {
            // On wing in final third: cross/center are options but SHOT still frequent
            // NO PASS in final rows (6-7 for HOME, 1-2 for AWAY)
            options = new String[] {"CROSS", "CENTER", "CARRY", "SHOT", "SHOT"};
        } else if (inFinalThird && !onWing) {
            // In final third central: NO PASS in final rows (6-7 for HOME, 1-2 for AWAY)
            options = new String[] {"CENTER", "CARRY", "SHOT", "SHOT"};
        } else if (canShoot) {
            options = new String[] {"PASS", "CARRY", "SHOT", "SHOT"};
        } else {
            options = new String[] {"PASS", "CARRY"};
        }
        String action = options[state.getRandom().nextInt(options.length)];

        switch (action) {
            case "PASS" -> actions.executePass();
            case "CLEAR" -> actions.executeClearance();
            case "CARRY" -> actions.executeCarry();
            case "CROSS" -> actions.executeCross();
            case "CENTER" -> actions.executeCenter();
            default -> actions.executeShot();
        }

        tactics.assignTargets();
        state.recordDesiredPositions();
        state.incrementRound();
        return state.getStatus();
    }

    private Player closestOf(Player home, Player away, Position ball) {
        if (home == null) return away;
        if (away == null) return home;
        double homeDistance = MovementEngine.distance(home.getPosition(), ball);
        double awayDistance = MovementEngine.distance(away.getPosition(), ball);
        return homeDistance <= awayDistance ? home : away;
    }
}
