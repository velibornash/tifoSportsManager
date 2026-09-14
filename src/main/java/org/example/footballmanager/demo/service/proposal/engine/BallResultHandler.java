package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.BallStepResult;
import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.model.Position;
import org.example.footballmanager.demo.service.proposal.recording.MatchRecorder;
import org.example.footballmanager.demo.service.proposal.result.ProposalStatsCollector;
import org.example.footballmanager.demo.service.proposal.restarts.RestartManager;

import java.util.List;

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
    private final List<String> eventLog;

    public BallResultHandler(MatchState state, MatchRecorder recorder,
                             ProposalStatsCollector stats, RestartManager restartManager,
                             List<String> eventLog) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
        this.restartManager = restartManager;
        this.eventLog = eventLog;
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
                        eventMsg = "RECEIVE " + receiver.getLabel() + "(" + receiver.getRole() + ")"
                                + " at " + p(receiver.getPosition()) + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "RECEIVE", eventMsg, state);
                        state.incrementPassesCompleted();
                        stats.onPassCompleted(receiver.getTeam(), receiver.getId());
                    }
                    case INTERCEPT -> {
                        Player interceptor = state.getCarrier();
                        eventMsg = "INTERCEPT " + interceptor.getLabel() + "(" + interceptor.getRole() + ")"
                                + " at " + p(interceptor.getPosition()) + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "INTERCEPT", eventMsg, state);
                        stats.onInterception(interceptor.getTeam(), interceptor.getId());
                    }
                    case SAVE -> {
                        Player gk = state.getCarrier();
                        if (wasShot) {
                            // Real save: ball in flight was a SHOT, GK stopped it.
                            eventMsg = "*** SHOT_SAVED by " + gk.getLabel() + "(" + gk.getRole() + ")"
                                    + (shooter != null ? " | shot by " + shooter.getLabel() : "")
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "SHOT_SAVED", eventMsg, shooter, null);
                            stats.onSave(gk.getTeam());
                            state.setLastShooter(null); // shot outcome consumed
                        } else {
                            // GK picked up a fast ball not launched as a shot (pass/clear
                            // toward own goal) — a catch, not a save. No save stat.
                            eventMsg = "GK CATCH by " + gk.getLabel() + "(pass/clear toward own goal)"
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "GK_CATCH", eventMsg, gk, null);
                        }
                    }
                    case BLOCK -> {
                        // Only a fast ball in flight following a SHOT is a shot block;
                        // otherwise it's a body deflection of a pass/clear.
                        if (wasShot) {
                            eventMsg = "SHOT_BLOCKED " + res.getDetail() + " parried the shot"
                                    + (shooter != null ? " | shot by " + shooter.getLabel() : "")
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "SHOT_BLOCKED", eventMsg, shooter, null);
                            if (shooter != null) {
                                stats.onBlock("HOME".equals(shooter.getTeam()) ? "AWAY" : "HOME");
                                state.setLastShooter(null); // shot outcome consumed
                            }
                        } else {
                            eventMsg = "BLOCK " + res.getDetail() + " parried a fast ball"
                                    + " | ball" + p(state.getBall().getPosition());
                            log("ORC", eventMsg);
                            recorder.appendEvent(state.getMatchTicks(), "BLOCK", eventMsg, state);
                        }
                    }
                    case DEFLECT -> {
                        eventMsg = "DEFLECT off " + res.getDetail() + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "DEFLECT", eventMsg, state);
                        // Attribute the deflect to the team of the player whose body it
                        // struck (label is the player's short name).
                        stats.onDeflect(resolveTeamByLabel(res.getDetail()));
                    }
                    case POST_HIT -> {
                        eventMsg = "SHOT_POST hit the post"
                                + (wasShot && shooter != null ? " | shot by " + shooter.getLabel() : "")
                                + " | ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(),
                                wasShot ? "SHOT_POST" : "POST_HIT", eventMsg, shooter, null);
                        if (wasShot) state.setLastShooter(null); // shot outcome consumed
                    }
                    case GOAL -> {
                        String scorerTeam = res.getScorerTeam();
                        if ("HOME".equals(scorerTeam)) state.addHomeGoal();
                        else state.addAwayGoal();
                        // Scorer = last touch (set at launch; survives deflections). Falls
                        // back to the shooter for safety.
                        Player scorer = state.getLastTouchPlayer() != null ? state.getLastTouchPlayer() : shooter;
                        eventMsg = "*** GOAL " + scorerTeam
                                + (scorer != null ? " by " + scorer.getLabel() + "(" + scorer.getRole() + ")" : "")
                                + " - score " + state.getHomeGoals() + ":" + state.getAwayGoals() + " ***"
                                + " ball" + p(state.getBall().getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "GOAL", eventMsg, scorer, null);
                        if (scorer != null) stats.onGoal(scorerTeam, scorer.getId());
                        state.setLastShooter(null); // shot outcome consumed
                        // Reset for kickoff (clock keeps running)
                        String kickoffTeam = "HOME".equals(scorerTeam) ? "AWAY" : "HOME";
                        restartManager.handleKickoff(state, kickoffTeam);
                        log("RST", "kickoff -> ball at center, taker " + state.getCarrier().getLabel());
                    }
                    case OOB_ENTER -> {
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
                        eventMsg = "restart " + restartType + " ball" + p(state.getBall().getPosition())
                                + " taker " + (state.getRestartTaker() == null ? "none" : state.getRestartTaker().getLabel());
                        log("RST", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "RESTART", eventMsg, state);
                        stats.onRestart(restartType);
                    }
                    case OOB_CANCEL -> {
                        eventMsg = "OOB cancel — ball rolled back into play | ball" + p(state.getBall().getPosition());
                        log("BAL", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "OOB_CANCEL", eventMsg, state);
                    }
                    case LOOSE_PICKUP -> {
                        Player carrier = state.getCarrier();
                        eventMsg = "LOOSE BALL recovered by " + carrier.getLabel()
                                + " | ball" + p(state.getBall().getPosition()) + " " + carrier.getLabel() + p(carrier.getPosition());
                        log("ORC", eventMsg);
                        recorder.appendEvent(state.getMatchTicks(), "LOOSE_PICKUP", eventMsg, state);
                    }
                    case STOPPED -> {
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
        String line = "[" + minute() + "|" + tag + "] " + msg;
        eventLog.add(line);
        System.out.println(line);
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
