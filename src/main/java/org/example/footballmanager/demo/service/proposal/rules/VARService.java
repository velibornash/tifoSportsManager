package org.example.footballmanager.demo.service.proposal.rules;

import org.example.footballmanager.demo.service.proposal.engine.EngineInterfaces;
import org.example.footballmanager.demo.service.proposal.model.*;
import java.util.Random;

/**
 * VAR (Video Assistant Referee) - port done (P7#2).
 *
 * Ported from reference engine/rules/VARService.java (demo/service reference,
 * "port done (P7#2)" - 8/8 @Override, compile-green).
 *
 * Frequency gates per backlog: offside 4%, goal 4% (8% overturn), red 10%
 * (25% overturn; 2nd yellow never overturned), penalty 5% (30% / 20%),
 * yellow 10% (upgrade 8% / downgrade 12%).
 */
public class VARService implements EngineInterfaces.VARService {

    private final MatchState state;
    private final Random random;

    private String lastVARDecision = "NONE";

    static final double OFFSIDE_MERGE_THRESHOLD = 1.5;

    public VARService(MatchState state, Random random) {
        this.state = state;
        this.random = random;
    }

    @Override
    public boolean checkOffside(Player receiver, Position passOrigin, MatchState state) {
        lastVARDecision = "NONE";

        // Gate: review only ~4% of offsides
        if (random.nextDouble() > 0.04) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        // Signal to the viewer that a review is in progress before the verdict.
        String team = receiver.getTeam();
        logVARReviewStarted(team, "OFFSIDE - reviewing " + receiver.getLabel());

        // Margin-based overturn. Reference formula (engine VARService):
        //   overturnChance = max(0.05, 0.40 - (margin / 1.5) * 0.35)
        //   margin 0.0 -> 40% overturn; margin 1.5 -> 5% overturn.
        // Proposal MatchState holds the closest-defender offside line via
        // OffsideService's per-tick tracking (checkOffside port P7#1).
        double margin = state.getOffsideDeferredMargin();
        double overturnChance = Math.max(0.05, 0.40 - (margin / OFFSIDE_MERGE_THRESHOLD) * 0.35);

        boolean overturned = random.nextDouble() < overturnChance;

        lastVARDecision = overturned ? "OFFSIDE_OVERTURNED" : "OFFSIDE_CONFIRMED";
        return !overturned;
    }

    @Override
    public boolean checkGoal(String scoringTeam, Position goalPosition) {
        lastVARDecision = "NONE";

        // Gate: review only ~4% of goals
        if (random.nextDouble() > 0.04) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        String defendingTeam = "HOME".equals(scoringTeam) ? "AWAY" : "HOME";
        logVARReviewStarted(scoringTeam, "GOAL - reviewing build-up for " + scoringTeam);

        // ~8% chance of goal being overturned (foul in buildup, offside, handball)
        boolean overturned = random.nextDouble() < 0.08;

        lastVARDecision = overturned ? "GOAL_OVERTURNED" : "GOAL_CONFIRMED";
        return !overturned;
    }

    @Override
    public boolean checkRedCard(Player defender, boolean isSecondYellow) {
        lastVARDecision = "NONE";

        // Gate: review only ~10% of red cards
        if (random.nextDouble() > 0.10) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        logVARReviewStarted(defender.getTeam(),
                "RED CARD - reviewing " + defender.getLabel() + " tackle");

        // Second yellows are almost never overturned by VAR
        if (isSecondYellow) {
            lastVARDecision = "RED_CONFIRMED";
            return true;
        }

        // Straight reds: ~25% chance of overturn (reduced to yellow)
        boolean overturned = random.nextDouble() < 0.25;

        lastVARDecision = overturned ? "RED_OVERTURNED" : "RED_CONFIRMED";
        return !overturned;
    }

    @Override
    public boolean checkPenalty(Position foulPosition, boolean homeAttacking) {
        lastVARDecision = "NONE";

        // Gate: review only ~5% of penalties
        if (random.nextDouble() > 0.05) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        String attackingTeam = homeAttacking ? "HOME" : "AWAY";
        logVARReviewStarted(attackingTeam,
                "PENALTY - reviewing " + (homeAttacking ? "HOME" : "AWAY") + " penalty claim");

        // ~20% chance of penalty being overturned; ~30% if review favours defending team
        boolean overturned = random.nextDouble() < 0.20;

        lastVARDecision = overturned ? "PENALTY_OVERTURNED" : "PENALTY_CONFIRMED";
        return !overturned;
    }

    @Override
    public String checkYellowCard(Player defender) {
        lastVARDecision = "NONE";

        // Gate: review only ~10% of yellow cards
        if (random.nextDouble() > 0.10) {
            lastVARDecision = "NO_REVIEW";
            return "CONFIRMED";
        }

        logVARReviewStarted(defender.getTeam(),
                "YELLOW CARD - reviewing " + defender.getLabel() + " tackle");

        // ~8% chance of upgrading yellow to red (dangerous tackle)
        if (random.nextDouble() < 0.08) {
            lastVARDecision = "YELLOW_UPGRADED_TO_RED";
            return "UPGRADE_TO_RED";
        }

        // ~12% chance of downgrading yellow to no card (no foul)
        if (random.nextDouble() < 0.12) {
            lastVARDecision = "YELLOW_DOWNGRADED";
            return "DOWNGRADE_TO_NONE";
        }

        lastVARDecision = "YELLOW_CONFIRMED";
        return "CONFIRMED";
    }

    @Override
    public void logVARDecision(String incidentType, String detail) {
        lastVARDecision = "VAR[" + incidentType + "]: " + detail;
    }

    @Override
    public void recordVARDecision(String eventType, String detail) {
        lastVARDecision = eventType + ": " + detail;
    }

    @Override
    public void logVARReviewStarted(String team, String reviewType) {
        lastVARDecision = "VAR_REVIEW[" + team + "]: " + reviewType;
        state.setPendingVARReview(reviewType, null, team);
    }

    public String getLastVARDecision() {
        return lastVARDecision;
    }
}
