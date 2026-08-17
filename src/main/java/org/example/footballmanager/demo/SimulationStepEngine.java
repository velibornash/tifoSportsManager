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
            carrier = selection.closestHomeTo(state.getBall().getPosition());
            if (MovementEngine.distance(carrier.getPosition(), state.getBall().getPosition()) > 1e-9) {
                state.setCarrier(carrier);
                selection.clearChaseTargetsExcept(carrier);
                carrier.setTarget(state.getBall().getPosition());
                state.recordDesiredPositions();
                actions.start(Action.Type.CHASE, carrier.getLabel() + " chasing ball");
                state.incrementRound();
                return state.getStatus();
            }
            // Preuzimanje je dozvoljeno samo na TACNOJ koordinati lopte.
            state.getBall().setCarrier(carrier);
            state.setCarrier(carrier);
        } else if (state.getBall().getCarrier() != carrier) {
            if (MovementEngine.distance(carrier.getPosition(), state.getBall().getPosition()) > 1e-9) {
                selection.clearChaseTargetsExcept(carrier);
                carrier.setTarget(state.getBall().getPosition());
                state.recordDesiredPositions();
                actions.start(Action.Type.CHASE, carrier.getLabel() + " moving to ball");
                state.incrementRound();
                return state.getStatus();
            }
            state.getBall().setCarrier(carrier);
            state.setCarrier(carrier);
            // Clear target since we've reached the ball
            carrier.setTarget(null);
        }

        double row = carrier.getPosition().getRow();
        if (state.isAwayRestartPending()) {
            state.setAwayRestartPending(false);
            if (!SimulationState.TEAM_HOME.equals(carrier.getTeam())) {
                state.setReturningPlayer(carrier);
                carrier.setTarget(carrier.getAlternativePosition());
                if (state.getCornerTaker() == carrier) {
                    Player cornerReceiver = selection.nearestAwayTo(new Position(3.5, 3.5), true, carrier);
                    state.setCornerTaker(null);
                    if (cornerReceiver != null) {
                        actions.executePassTo(cornerReceiver);
                    } else {
                        actions.executePassTo(selection.closestHomeGoalkeeper());
                    }
                } else {
                    actions.executePassTo(selection.closestHomeGoalkeeper());
                }
            } else {
                // Gol-kick: HOME golman izvodi loptu sa svoje pozicije.
                actions.executePass();
            }
            state.incrementRound();
            return state.getStatus();
        }
        boolean goalkeeper = "GK".equals(carrier.getRole());
        boolean canShoot = row >= ActionEngine.SHOOT_MIN_ROW && !goalkeeper;
        String[] options = goalkeeper
            ? new String[] {"PASS", "CLEAR"}
            : canShoot
                ? new String[] {"PASS", "CARRY", "SHOT"}
                : new String[] {"PASS", "CARRY"};
        String action = options[state.getRandom().nextInt(options.length)];

        switch (action) {
            case "PASS" -> actions.executePass();
            case "CLEAR" -> actions.executeClearance();
            case "CARRY" -> actions.executeCarry();
            default -> actions.executeShot();
        }

        tactics.assignTargets();
        state.recordDesiredPositions();
        state.incrementRound();
        return state.getStatus();
    }
}
