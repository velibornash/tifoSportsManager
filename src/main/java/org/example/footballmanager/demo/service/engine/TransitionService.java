package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;

/**
 * Transition Service — corePrinciples Section 21.
 *
 * "When possession changes, players should not instantly teleport
 * from their previous tactical roles into their new roles."
 *
 * Models the moment of transition: counterattacks, defensive recovery,
 * pressing, exposed spaces, temporary numerical advantages.
 *
 * Transitions should emerge naturally from the simulation.
 */
public class TransitionService {

    private final MatchState state;
    private final MatchRecorder recorder;

    private String previousPossessionTeam = null;
    private int transitionTick = 0;
    private static final int TRANSITION_WINDOW_TICKS = 60; // ~0.5 seconds of transition

    public TransitionService(MatchState state, MatchRecorder recorder) {
        this.state = state;
        this.recorder = recorder;
    }

    /**
     * Detect and handle possession change.
     * Called every tick to check if possession has changed.
     */
    public void checkTransition() {
        String currentTeam = state.getCarrier() != null ? state.getCarrier().getTeam() : null;

        if (currentTeam != null && previousPossessionTeam != null
                && !currentTeam.equals(previousPossessionTeam)) {
            onPossessionChange(previousPossessionTeam, currentTeam);
        }

        previousPossessionTeam = currentTeam;
    }

    private void onPossessionChange(String oldTeam, String newTeam) {
        transitionTick = (int) state.getSimulationTick();

        // Record transition event
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                null, "POSSESSION_CHANGE",
                "Possession changed: " + oldTeam + " → " + newTeam);

        // Determine match phase based on where ball is
        Position ballPos = state.getBall().getPosition();
        boolean inAttackingThird = "HOME".equals(newTeam)
                ? ballPos.getRow() >= 5
                : ballPos.getRow() <= 3;

        if (inAttackingThird) {
            state.setPhase(MatchPhase.TRANSITION_TO_ATTACK);
        } else {
            state.setPhase(MatchPhase.TRANSITION_TO_DEFENSE);
        }
    }

    /**
     * Check if the match is still in a transition phase.
     * Transition phase expires after TRANSITION_WINDOW_TICKS.
     */
    public boolean isInTransition() {
        return state.getPhase() == MatchPhase.TRANSITION_TO_ATTACK
                || state.getPhase() == MatchPhase.TRANSITION_TO_DEFENSE;
    }

    /**
     * Get transition urgency for a player.
     * Higher urgency = player reacts faster to the new situation.
     * @param player the player to evaluate
     * @return 0-1 urgency score
     */
    public double transitionUrgency(Player player) {
        if (!isInTransition()) return 0.0;

        int ticksSinceTransition = (int) state.getSimulationTick() - transitionTick;
        if (ticksSinceTransition > TRANSITION_WINDOW_TICKS) return 0.0;

        double timeDecay = 1.0 - ((double) ticksSinceTransition / TRANSITION_WINDOW_TICKS);

        boolean sameTeamAsNewCarrier = state.getCarrier() != null
                && player.getTeam().equals(state.getCarrier().getTeam());

        // Attacking transition: forwards and wingers have higher urgency
        if (state.getPhase() == MatchPhase.TRANSITION_TO_ATTACK) {
            if (sameTeamAsNewCarrier) {
                return player.isAttacker() ? timeDecay * 1.0 : timeDecay * 0.6;
            }
            return timeDecay * 0.8; // defending team: recover quickly
        }

        // Defensive transition: defenders have higher urgency
        if (state.getPhase() == MatchPhase.TRANSITION_TO_DEFENSE) {
            if (sameTeamAsNewCarrier) {
                return timeDecay * 0.7; // attacking team: keep shape
            }
            return player.isDefender() || player.isGoalkeeper()
                    ? timeDecay * 1.0 : timeDecay * 0.7;
        }

        return 0.0;
    }

    /** Update phase based on ball position and possession state. */
    public void updatePhase() {
        if (isInTransition()) return; // let transition play out
        if (state.getPhase() == MatchPhase.GOAL) return;

        String carrierTeam = state.getCarrier() != null ? state.getCarrier().getTeam() : null;
        if (carrierTeam == null) {
            state.setPhase(MatchPhase.OPEN_PLAY);
            return;
        }

        Position ballPos = state.getBall().getPosition();
        boolean inAttackingHalf = "HOME".equals(carrierTeam)
                ? ballPos.getRow() >= 4
                : ballPos.getRow() <= 4;

        if (inAttackingHalf) {
            state.setPhase(MatchPhase.ATTACK);
        } else {
            state.setPhase(MatchPhase.DEFENSE);
        }
    }
}
