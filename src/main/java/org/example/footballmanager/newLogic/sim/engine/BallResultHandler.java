package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.ActionType;
import org.example.footballmanager.newLogic.sim.model.BallStepResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.rules.VARService;

/**
 * Handles a single ball-physics step result: converts low-level physics
 * outcomes (RECEIVE/INTERCEPT/SAVE/BLOCK/DEFLECT/POST/GOAL/OOB/LOOSE/...)
 * into match consequences — recorder events, stats, restarts, and shots
 * epilogues. Slims the orchestrator: it now just delegates here.
 */
public class BallResultHandler {

    private final MatchState state;
    private final MatchRecorder recorder;
    private final ProposalStatsCollector stats;
    private final RestartManager restartManager;
    private final VARService varService;

    public BallResultHandler(MatchState state, MatchRecorder recorder,
                             ProposalStatsCollector stats, RestartManager restartManager) {
        this(state, recorder, stats, restartManager, null);
    }

    public BallResultHandler(MatchState state, MatchRecorder recorder,
                             ProposalStatsCollector stats, RestartManager restartManager,
                             VARService varService) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
        this.restartManager = restartManager;
        this.varService = varService;
    }

    public void handle(BallStepResult res) {
                String eventMsg = "";
                // "wasShot" = a shot is in flight awaiting its outcome. Uses lastShooter
                // as the pending-shot flag (cleared once the outcome is consumed so a
                // single shot never emits multiple SHOT_MISSED/SHOT_SAVED etc.).
                boolean wasShot = state.getLastShooter() != null;
                Player shooter = state.getLastShooter(); // attribution for shot epilogue
                switch (res.getType()) {
                    case RECEIVE -> {
                        Player receiver = state.getCarrier(); // already set by ball engine
                        Player flagged = state.getOffsideFlaggedReceiver();
                        if (flagged != null && flagged == receiver) {
                            // OFFSIDE AT RECEPTION (user rule 2026-09-23): the pass
                            // flew normally from the passer (no teleport, no sudden
                            // acceleration). The whistle fires NOW, at the exact spot
                            // where the flagged receiver physically touched the ball.
                            state.setOffsideFlaggedReceiver(null);
                            Position spot = state.getBall().getPosition();
                            String defendingTeam = "HOME".equals(receiver.getTeam()) ? "AWAY" : "HOME";
                            state.setRestartTeam(defendingTeam);
                            state.setCarrier(null);
                            state.getBall().stop();
                            String offMsg = "*** OFFSIDE by " + receiver.getLabel()
                                    + " - indirect free kick " + defendingTeam
                                    + " at " + p(spot) + " (whistle at reception)";
                            log("ORC", offMsg);
                            recorder.appendEvent(state.getMatchTicks(), "OFFSIDE", offMsg, state);
                            // No pass completed / no receive stat — play is dead.
                            // This path does not go through OffsideService, so the
                            // offside counter has to be fed here as well.
                            stats.onOffside(receiver.getTeam());
                            restartManager.handleOffsideFreeKick(state, spot);
                            state.clearPassContext();
                            log("RST", "offside IFK -> taker "
                                    + (state.getRestartTaker() == null ? "none"
                                        : state.getRestartTaker().getLabel())
                                    + " at ball" + p(state.getBall().getPosition()));
                            break; // skip normal receive counters
                        }
                        eventMsg = "RECEIVE " + receiver.getLabel() + "(" + receiver.getRole() + ")"
                                + " at " + p(receiver.getPosition()) + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "RECEIVE", eventMsg, state);
                        String completedPasserId = state.completePass(receiver);
                        state.incrementPassesCompleted();
                        stats.onPassCompleted(receiver.getTeam(), receiver.getId(), completedPasserId);
                    }
                    case INTERCEPT -> {
                        Player interceptor = state.getCarrier();
                        state.setOffsideFlaggedReceiver(null); // defender reached it first — no offense
                        eventMsg = "INTERCEPT " + interceptor.getLabel() + "(" + interceptor.getRole() + ")"
                                + " at " + p(interceptor.getPosition()) + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "INTERCEPT", eventMsg, state);
                        state.clearPassContext();
                        stats.onInterception(interceptor.getTeam(), interceptor.getId());
                    }
                    case SAVE -> {
                        Player gk = state.getCarrier();
                        state.setOffsideFlaggedReceiver(null);
                        if (wasShot) {
                            // Real save: ball in flight was a SHOT, GK stopped it.
                            eventMsg = "*** SHOT_SAVED by " + gk.getLabel() + "(" + gk.getRole() + ")"
                                    + (shooter != null ? " | shot by " + shooter.getLabel() : "")
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "SHOT_SAVED", eventMsg, shooter, null);
                            state.clearPassContext();
                            stats.onSave(gk.getTeam());
                            state.setLastShooter(null); // shot outcome consumed
                        } else {
                            // GK picked up a fast ball not launched as a shot (pass/clear
                            // toward own goal) — a catch, not a save. No save stat.
                            eventMsg = "GK CATCH by " + gk.getLabel() + "(pass/clear toward own goal)"
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "GK_CATCH", eventMsg, gk, null);
                            state.clearPassContext();
                        }
                    }
                    case BLOCK -> {
                        state.setOffsideFlaggedReceiver(null);
                        // Only a fast ball in flight following a SHOT is a shot block;
                        // otherwise it's a body deflection of a pass/clear.
                        if (wasShot) {
                            eventMsg = "SHOT_BLOCKED " + res.getDetail() + " parried the shot"
                                    + (shooter != null ? " | shot by " + shooter.getLabel() : "")
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "SHOT_BLOCKED", eventMsg, shooter, null);
                            state.clearPassContext();
                            if (shooter != null) {
                                stats.onBlock("HOME".equals(shooter.getTeam()) ? "AWAY" : "HOME");
                                state.setLastShooter(null); // shot outcome consumed
                            }
                        } else {
                            eventMsg = "BLOCK " + res.getDetail() + " parried a fast ball"
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "BLOCK", eventMsg, state);
                            state.clearPassContext();
                        }
                    }
                    case DEFLECT -> {
                        state.setOffsideFlaggedReceiver(null);
                        eventMsg = "DEFLECT off " + res.getDetail() + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "DEFLECT", eventMsg, state);
                        state.clearPassContext();
                        // Attribute the deflect to the team of the player whose body it
                        // struck (label is the player's short name).
                        stats.onDeflect(resolveTeamByLabel(res.getDetail()));
                    }
                    case POST_HIT -> {
                        state.setOffsideFlaggedReceiver(null);
                        eventMsg = "SHOT_POST hit the post"
                                + (wasShot && shooter != null ? " | shot by " + shooter.getLabel() : "")
                                + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(),
                                wasShot ? "SHOT_POST" : "POST_HIT", eventMsg, shooter, null);
                        if (wasShot) state.setLastShooter(null); // shot outcome consumed
                        state.clearPassContext();
                    }
                    case GOAL -> {
                        state.setOffsideFlaggedReceiver(null);
                        // Goal only valid from a shot – prevents goals from passes/deflections crossing the line
                        boolean isShotGoal = wasShot && state.getLastActionType() == ActionType.SHOT;
                        if (!isShotGoal) {
                            // Treat accidental line crossing as OOB/goal kick, do not award
                            String goalTeam = res.getScorerTeam();
                            String kickoffTeam = "HOME".equals(goalTeam) ? "AWAY" : "HOME";
                            restartManager.handleKickoff(state, kickoffTeam);
                            log("RST", "illegal goal crossing ignored, forced kickoff -> " + kickoffTeam);
                            return;
                        }
                        String scorerTeam = res.getScorerTeam();

                        // VAR goal check — a goal can be overturned for offside, a
                        // foul in the build-up, a handball or a disallowed restart.
                        // {@code checkGoal} existed but was never called from
                        // anywhere, so this whole overturn path was dead code and
                        // VAR never changed a goal. Run it BEFORE the goal is
                        // counted, so an overturned goal leaves no trace in the
                        // scoreline or the stats.
                        if (varService != null
                                && !varService.checkGoal(scorerTeam, state.getBall().getPosition())) {
                            String defending = "HOME".equals(scorerTeam) ? "AWAY" : "HOME";
                            String overturnedMsg = "*** GOAL DISALLOWED by VAR for "
                                    + scorerTeam + " — free kick to " + defending;
                            log("VAR", overturnedMsg);
                            recorder.appendEvent(state.getMatchTicks(), "GOAL_DISALLOWED",
                                    overturnedMsg, state);
                            recorder.appendEvent(state.getMatchTicks(), "VAR_GOAL_OVERTURNED",
                                    "VAR GOAL OVERTURNED — " + overturnedMsg,
                                    scorerTeam, state.getLastTouchPlayer(), null,
                                    null, null,
                                    Integer.valueOf(state.getHomeGoals()),
                                    Integer.valueOf(state.getAwayGoals()),
                                    null, null, null, null, "GOAL", "OVERTURNED");
                            state.setLastShooter(null);
                            state.clearPassContext();
                            state.setRestartTeam(defending);
                            restartManager.handleFreeKick(state,
                                    state.getBall().getPosition(), defending);
                            return;
                        }

                        if ("HOME".equals(scorerTeam)) state.addHomeGoal();
                        else state.addAwayGoal();
                        Player scorer = state.getLastTouchPlayer() != null ? state.getLastTouchPlayer() : shooter;
                        String assistId = state.assistIdFor(scorer);
                        String assistName = state.assistNameFor(scorer);
                        eventMsg = "*** GOAL " + scorerTeam
                                + (scorer != null ? " by " + scorer.getLabel() + "(" + scorer.getRole() + ")" : "")
                                + (assistName != null ? " assisted by " + assistName : "")
                                + " - score " + state.getHomeGoals() + ":" + state.getAwayGoals() + " ***"
                                + " ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "GOAL", eventMsg, scorer, null,
                                assistId, assistName, state.getHomeGoals(), state.getAwayGoals(),
                                null, null, null, null, null, null);
                        if (scorer != null) stats.onGoal(scorerTeam, scorer.getId(), assistId);
                        state.clearPassContext();
                        state.setLastShooter(null); // shot outcome consumed
                        // Reset for kickoff (clock keeps running)
                        String kickoffTeam = "HOME".equals(scorerTeam) ? "AWAY" : "HOME";
                        restartManager.handleKickoff(state, kickoffTeam);
                        // handleKickoff leaves the carrier null when it cannot find
                        // a kicker (all attackers unavailable) — never dereference it.
                        Player kicker = state.getCarrier();
                        log("RST", "kickoff -> ball at center, taker "
                                + (kicker == null ? "none (no kicker available)" : kicker.getLabel()));
                    }
                    case OOB_ENTER -> {
                        state.setOffsideFlaggedReceiver(null);
                        eventMsg = "OOB enter -> " + res.getRestartType() + " (hold " + BallPhysicsEngine.OOB_HOLD_TICKS + " ticks) | ball" + p(state.getBall().getPosition());
                        log("BAL", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "OOB_ENTER", eventMsg, state);
                        // A shot that leaves the field is a miss (goal kick / corner after).
                        if (wasShot) {
                            String missMsg = "SHOT_MISSED by " + (shooter != null ? shooter.getLabel() : "?")
                                    + " -> " + res.getRestartType();
                            log("ORC", missMsg);
                            recorder.appendEvent(state.getMatchTicks(), "SHOT_MISSED", missMsg, shooter, null);
                            state.setLastShooter(null); // shot outcome consumed
                        }
                        state.clearPassContext();
                    }
                    case OOB_HOLD -> {
                        eventMsg = "OOB hold " + res.getDetail() + " | ball" + p(state.getBall().getPosition());
                        log("BAL", eventMsg);
                    }
                    case OOB_RESTART -> {
                        String restartType = res.getRestartType();
                        // Pass the OOB exit position so restarts land on the CORRECT side:
                        // throw-ins at the exit touchline, corners on the exit side's corner
                        // (user-reported bug 2026-09-14: ball out right col 6->7, throw-in
                        // restarted on LEFT col 1 — positions were hardcoded).
                        Position oobExit = state.getBall().getPosition();
                        restartManager.handleRestart(state, restartType, oobExit);
                        state.clearPassContext();
                        eventMsg = "restart " + restartType + " ball" + p(state.getBall().getPosition())
                                + " taker " + (state.getRestartTaker() == null ? "none" : state.getRestartTaker().getLabel());
                        log("RST", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "RESTART", eventMsg, state);
                        stats.onRestart(restartType);
                    }
                    case OOB_CANCEL -> {
                        state.setOffsideFlaggedReceiver(null);
                        state.clearPassContext();
                        eventMsg = "OOB cancel — ball rolled back into play | ball" + p(state.getBall().getPosition());
                        log("BAL", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "OOB_CANCEL", eventMsg, state);
                    }
                    case LOOSE_PICKUP -> {
                        Player carrier = state.getCarrier();
                        state.setOffsideFlaggedReceiver(null);
                        eventMsg = "LOOSE BALL recovered by " + carrier.getLabel()
                                + " | ball" + p(state.getBall().getPosition()) + " " + carrier.getLabel() + p(carrier.getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "LOOSE_PICKUP", eventMsg, state);
                        state.clearPassContext();
                    }
                    case STOPPED -> {
                        state.setOffsideFlaggedReceiver(null); // ball died, no touch by the flagged receiver
                        // Ball died on the pitch. If it was a shot (off target / didn't reach
                        // the goal), emit the missing epilogue so the sidebar shows the full
                        // shot outcome chain: SHOT → SAVED/BLOCKED/POST/MISSED.
                        if (wasShot) {
                            String missMsg = "SHOT_MISSED by " + (shooter != null ? shooter.getLabel() : "?")
                                    + " — ball died " + p(state.getBall().getPosition());
                            log("ORC", missMsg);
                            recorder.appendEvent(state.getMatchTicks(), "SHOT_MISSED", missMsg, shooter, null);
                            state.setLastShooter(null); // shot outcome consumed
                        }
                        if (wasShot || state.getPendingReceiver() == null) {
                            state.clearPassContext();
                        }
                    }
                    case FLIGHT -> {
                        // ball in flight, no event needed
                    }
                }
}


    private String resolveTeamByLabel(String label) {
        if (label == null) return null;
        for (Player p : state.getPlayers()) {
            if (label.equals(p.getLabel())) return p.getTeam();
        }
        return null;
    }

    private void log(String tag, String msg) {
        // Route through the shared action logger so the compact-console filter
        // and the full-log file (target/proposal-app.log) apply uniformly.
        state.getActionLogger().log(tag, msg);
    }

    private String p(Position pos) {
        return pos == null ? "?" : "(%.1f,%.1f)".formatted(pos.getRow(), pos.getColumn());
    }

    private String minute() {
        return String.format("%d:%02d",
                state.getMatchTicks() / 40,
                state.getMatchTicks() % 40 * 90 / 40);
    }
}
