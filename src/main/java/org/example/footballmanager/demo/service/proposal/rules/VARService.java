package org.example.footballmanager.demo.service.proposal.rules;

import org.example.footballmanager.demo.service.proposal.engine.EngineInterfaces;
import org.example.footballmanager.demo.service.proposal.model.*;
import java.util.Random;

/**
 * VAR (Video Assistant Referee) — placeholder stub.
 *
 * Responsibilities (filled in later per backlog):
 * - Offside review (automatic on goals + on-demand during play)
 * - Goal decision review
 * - Red card review (second yellow + straight red)
 * - Penalty area incident review
 *
 * Frequency gates: each incident type only triggers a review X% of the
 * time to avoid excessive stoppages (demo/service reference: offside 20%,
 * goal 15%, penalty 25%, red 40%).
 */
public class VARService implements EngineInterfaces.VARService {

    private final MatchState state;
    private final Random random;
    private String lastVARDecision = "";

    public VARService(MatchState state, Random random) {
        this.state = state;
        this.random = random;
    }

    @Override
    public boolean checkOffside(Player receiver, Position passOrigin, MatchState state) {
        // TODO: offside review with frequency gate (20%)
        return false;
    }

    @Override
    public boolean checkGoal(String scoringTeam, Position goalPosition) {
        // TODO: goal review with frequency gate (15%)
        return false;
    }

    @Override
    public boolean checkRedCard(Player defender, boolean isSecondYellow) {
        // TODO: red card review with frequency gate (40%)
        return false;
    }

    @Override
    public boolean checkPenalty(Position foulPosition, boolean homeAttacking) {
        // TODO: penalty review with frequency gate (25%)
        return false;
    }

    @Override
    public String checkYellowCard(Player defender) {
        // TODO: yellow card check
        return "";
    }

    @Override
    public void logVARDecision(String incidentType, String detail) {
        lastVARDecision = "VAR[" + incidentType + "]: " + detail;
        // TODO: emit VAR_IN_PROGRESS / VAR_*_CONFIRMED / VAR_*_OVERTURNED event
    }

    @Override
    public void recordVARDecision(String eventType, String detail) {
        lastVARDecision = eventType + ": " + detail;
        // TODO: record in MatchRecorder
    }

    @Override
    public void logVARReviewStarted(String team, String reviewType) {
        lastVARDecision = "VAR_REVIEW[" + team + "]: " + reviewType;
        // TODO: emit VAR review start event
    }

    public String getLastVARDecision() { return lastVARDecision; }
}
