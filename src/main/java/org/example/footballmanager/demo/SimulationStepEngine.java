package org.example.footballmanager.demo;

/**
 * Odgovornost: ODLUKA / KORAK SIMULACIJE.
 *
 * Obradjuje JEDNU rundu (jedan {@link SimulationEngine#step() step()}) koristeci
 * POSTOJECA pravila:
 *  - ceka ako je pas/sut u letu ili ako nosilac jos hoda
 *  - CHASE kad nema nosioca ili je daleko od lopte
 *  - playmaking-informisana odluka PASS/THRU/CARRY/CLEAR/SHOT/CROSS/CENTER
 *    (zamena nasumičnom izboru — {@link PlaymakingDecisionEngine})
 *  - dodela taktickih ciljeva i snimanje desired pozicija
 *
 * Playmaking određuje kvalitet odluke (šta igrač vidi i koliko često bira
 * najbolje), NE kvalitet izvođenja (to je {@link ExecutionQuality}).
 * Odluka se NE menja tokom akcije; samo je iza ciste arhitektonske granice.
 */
public class SimulationStepEngine {

    private final SimulationState state;
    private final PlayerSelectionEngine selection;
    private final ActionEngine actions;
    private final TacticalIntentEngine tactics;
    private final PlaymakingDecisionEngine playmakingEngine;

    public SimulationStepEngine(SimulationState state, PlayerSelectionEngine selection,
                                ActionEngine actions, TacticalIntentEngine tactics,
                                PlaymakingDecisionEngine playmakingEngine) {
        this.state = state;
        this.selection = selection;
        this.actions = actions;
        this.tactics = tactics;
        this.playmakingEngine = playmakingEngine;
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
                // Check if players from BOTH teams are already converging on the ball —
                // contested loose ball at center. If so, skip kickoff placement
                // and let CHASE logic handle it.
                Position ballPos = state.getBall().getPosition();
                boolean homeNear = state.getPlayers().stream()
                        .filter(p -> SimulationState.TEAM_HOME.equals(p.getTeam()))
                        .anyMatch(p -> MovementEngine.distance(p.getPosition(), ballPos) < 1.0);
                boolean awayNear = state.getPlayers().stream()
                        .filter(p -> p.getTeam().equals("AWAY"))
                        .anyMatch(p -> MovementEngine.distance(p.getPosition(), ballPos) < 1.0);

                if (!homeNear || !awayNear) {
                    // Normal kickoff — no players already close to ball
                    // Kickoff: spic mora tačno doći na loptu (red 4, granica 3-4)
                    // Prva akcija mora biti pass unazad
                    String kickoffTeam = state.getKickoffTeam();
                    Player striker = selection.teamByRole(kickoffTeam, "ST");
                    if (striker == null) {
                        // Fallback to closest forward if no ST
                        boolean home = SimulationState.TEAM_HOME.equals(kickoffTeam);
                        for (Player p : state.getPlayers()) {
                            if (!kickoffTeam.equals(p.getTeam())) continue;
                            if (home && p.getPosition().getRow() >= 4) {
                                striker = p;
                                break;
                            }
                            if (!home && p.getPosition().getRow() <= 4) {
                                striker = p;
                                break;
                            }
                        }
                    }
                    if (striker == null) {
                        striker = selection.closestTeamTo(state.getBall().getPosition(), kickoffTeam);
                    }

                    // Postavi spic tačno na loptu (red 4, granica 3-4)
                    Position centerSpot = new Position(4, 3.5);
                    striker.setPosition(centerSpot);
                    striker.setTarget(null);
                    state.getBall().setPosition(centerSpot);
                    state.getBall().setCarrier(striker);
                    state.setCarrier(striker);
                    state.setKickoffPending(false);
                    state.setKickoffTeam(kickoffTeam);
                    state.clearActiveChasers();
                    state.setStatus("KICKOFF: " + striker.getLabel() + " at center (4, 3.5)");
                    state.setRoundComplete(true);
                    state.log("KICKOFF: " + striker.getLabel() + " at center (4, 3.5)");
                    state.recordDesiredPositions();
                    state.incrementRound();
                    return state.getStatus();
                }
                // Players from both teams converging on ball — skip kickoff, fall through to CHASE
                state.setKickoffPending(false);
            }

            // Loose ball CHASE setup (runs when kickoff skipped due to convergence,
            // or when no kickoff is pending)
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
            if (carrier == null) carrier = selection.closestHomeTo(state.getBall().getPosition());
            if (MovementEngine.distance(carrier.getPosition(), state.getBall().getPosition()) > 0.01) {
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
            carrier.setPosition(state.getBall().getPosition());
            state.getBall().setCarrier(carrier);
            state.setCarrier(carrier);
            state.setKickoffTeam(SimulationState.TEAM_HOME);
            state.clearActiveChasers();
        } else if (state.getBall().getCarrier() != carrier) {
            if (MovementEngine.distance(carrier.getPosition(), state.getBall().getPosition()) > 0.01) {
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
            // Preuzimanje je dozvoljeno samo na TACNOJ koordinati lopte.
            carrier.setPosition(state.getBall().getPosition());
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

        // Provera za kickoff ili gol - prva akcija mora biti pass unazad
        boolean isKickoffAction = row == 4 && carrier.getPosition().getColumn() == 3.5
                && (state.getRound() == 1 || state.isCelebrating());

        // Playmaking-based decision (zamena nasumičnom izboru).
        // Engine generiše opcije, filtrira po PM vidnom tieru, boduje i bira.
        // THRU i PASS biraju se direktno — executePass() više nije pozvan
        // (on je imao ugrađenu 40% THRU logiku koju playmaking sada upravlja).
        DecisionOption decision = playmakingEngine.decide();
        ThreatEngine threat = tactics.getThreatEngine();
        if (threat != null && threat.isEnabled()) {
            ThreatEngine.PassResult passSafety = threat.overrideCarrierPass(decision);
            if (passSafety.kind == ThreatEngine.PassResult.Kind.VIOLATION) {
                // Offside violation: ThreatEngine already scheduled the opponent
                // restart at the exact receiving position. The illegal pass is not executed.
                state.recordDesiredPositions();
                state.incrementRound();
                return state.getStatus();
            }
            if (passSafety.kind == ThreatEngine.PassResult.Kind.PASS_LEGAL) {
                decision = passSafety.replacement;
            }
        }
        executeDecisionOption(decision);

        tactics.assignTargets();
        state.recordDesiredPositions();
        state.incrementRound();
        return state.getStatus();
    }

    /**
     * Dispatches a playmaking DecisionOption to the corresponding ActionEngine
     * method. PASS and THRU route directly to executePassTo / executeThruPass
     * (bypassing the old executePass which had an internal 40% THRU roll).
     * If a PASS/THRU option has no target (no eligible receiver found),
     * falls back to executePass / executeClearance respectively.
     */
    private void executeDecisionOption(DecisionOption option) {
        switch (option.getType()) {
            case PASS -> {
                if (option.getTarget() != null) {
                    actions.executePassTo(option.getTarget());
                } else {
                    actions.executePass();
                }
            }
            case THRU -> actions.executeThruPass(option.getTarget());
            case CARRY -> actions.executeCarry();
            case CLEAR -> actions.executeClearance();
            case SHOT -> actions.executeShot();
            case CROSS -> actions.executeCross();
            case CENTER -> actions.executeCenter();
        }
    }

    private Player closestOf(Player home, Player away, Position ball) {
        if (home == null) return away;
        if (away == null) return home;
        double homeDistance = MovementEngine.distance(home.getPosition(), ball);
        double awayDistance = MovementEngine.distance(away.getPosition(), ball);
        return homeDistance <= awayDistance ? home : away;
    }
}
