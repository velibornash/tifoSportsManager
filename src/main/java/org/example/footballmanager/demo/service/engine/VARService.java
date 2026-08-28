package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.MatchRecorder;

import java.util.Random;

/**
 * VAR (Video Assistant Referee) Service.
 *
 * Checks incidents AFTER they occur — provisional results become confirmed or overturned.
 * VAR reviews: offside (close calls), goals, red cards, penalties near box edge.
 *
 * VAR does NOT initiate — it reacts to specific trigger conditions.
 * "Clear and obvious error" standard: VAR only overturns when evidence is strong.
 */
public class VARService {

    private static final double OFFSIDE_MERGE_THRESHOLD = 1.5;
    private static final double PENALTY_BOX_EDGE_DISTANCE = 1.0;

    private final MatchState state;
    private final Random random;
    private final MatchRecorder recorder;

    private String lastVARDecision = "NONE";

    public VARService(MatchState state, Random random, MatchRecorder recorder) {
        this.state = state;
        this.random = random;
        this.recorder = recorder;
    }

    public String getLastVARDecision() { return lastVARDecision; }

    /**
     * VAR check for offside.
     * Only triggers on "close" offside calls — where the receiver was barely ahead.
     *
     * @param receiver the player flagged offside
     * @param passOrigin where the pass was played from
     * @param defenders positions of non-GK defenders
     * @return true if offside is CONFIRMED, false if OVERTURNED
     */
    public boolean checkOffside(Player receiver, Position passOrigin, Player[] defenders) {
        lastVARDecision = "NONE";

        // VAR only reviews ~20% of offsides (close calls only)
        if (random.nextDouble() > 0.20) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        boolean home = "HOME".equals(receiver.getTeam());

        // Find the offside line: closest defender to the goal the attacker is targeting.
        // HOME attacks row 7 → offside line = max row among defenders (closest to row 7)
        // AWAY attacks row 1 → offside line = min row among defenders (closest to row 1)
        double offsideLineRow = Double.MAX_VALUE;
        for (Player d : defenders) {
            if (d == null) continue;
            double row = d.getPosition().getRow();
            if (home) {
                if (offsideLineRow == Double.MAX_VALUE || row > offsideLineRow) {
                    offsideLineRow = row;
                }
            } else {
                if (row < offsideLineRow) {
                    offsideLineRow = row;
                }
            }
        }
        if (offsideLineRow == Double.MAX_VALUE) {
            lastVARDecision = "OFFSIDE_CONFIRMED";
            return true;
        }

        double offsideMargin = home
                ? receiver.getPosition().getRow() - offsideLineRow
                : offsideLineRow - receiver.getPosition().getRow();

        // Clear offside (margin > 1.5 cells) — confirmed immediately, no VAR
        if (offsideMargin > OFFSIDE_MERGE_THRESHOLD) {
            lastVARDecision = "OFFSIDE_CONFIRMED";
            return true;
        }

        // Close offside — VAR reviews
        // VAR overturn probability: smaller margin = higher chance of overturn
        // margin 0.0 → 40% chance overturned; margin 1.5 → 5% chance overturned
        double overturnChance = Math.max(0.05, 0.40 - (offsideMargin / OFFSIDE_MERGE_THRESHOLD) * 0.35);
        boolean overturned = random.nextDouble() < overturnChance;

        lastVARDecision = overturned ? "OFFSIDE_OVERTURNED" : "OFFSIDE_CONFIRMED";
        return !overturned;
    }

    /**
     * VAR check for a goal.
     * Reviews potential fouls, offside, or handball in the buildup.
     *
     * @param scoringTeam the team that scored
     * @param goalPosition where the ball ended up
     * @return true if goal CONFIRMED, false if OVERTURNED (no goal)
     */
    public boolean checkGoal(String scoringTeam, Position goalPosition) {
        lastVARDecision = "NONE";

        // VAR only reviews ~15% of goals
        if (random.nextDouble() > 0.15) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        // ~8% chance of goal being overturned (foul in buildup, offside, handball)
        double overturnChance = 0.08;
        boolean overturned = random.nextDouble() < overturnChance;

        lastVARDecision = overturned ? "GOAL_OVERTURNED" : "GOAL_CONFIRMED";
        return !overturned;
    }

    /**
     * VAR check for a red card.
     * Reviews whether the foul merits a straight red.
     *
     * @param defender the player who committed the foul
     * @param isSecondYellow whether this would be a second yellow
     * @return true if red card CONFIRMED, false if OVERTURNED (reduced to yellow or no card)
     */
    public boolean checkRedCard(Player defender, boolean isSecondYellow) {
        lastVARDecision = "NONE";

        // VAR only reviews ~40% of red cards
        if (random.nextDouble() > 0.40) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        // Second yellows are almost never overturned by VAR
        if (isSecondYellow) {
            lastVARDecision = "RED_CONFIRMED";
            return true;
        }

        // Straight reds: ~25% chance of overturn (reduced to yellow)
        double overturnChance = 0.25;
        boolean overturned = random.nextDouble() < overturnChance;

        lastVARDecision = overturned ? "RED_OVERTURNED" : "RED_CONFIRMED";
        return !overturned;
    }

    /**
     * VAR check for a penalty decision.
     * Reviews fouls near the penalty box edge — was it inside or outside?
     *
     * @param foulPosition where the foul occurred
     * @param homeAttacking whether HOME is the attacking team
     * @param wasCalledPenalty whether the referee initially called a penalty
     * @return true if original decision CONFIRMED, false if OVERTURNED
     */
    public boolean checkPenalty(Position foulPosition, boolean homeAttacking, boolean wasCalledPenalty) {
        lastVARDecision = "NONE";

        // VAR only reviews ~25% of penalties
        if (random.nextDouble() > 0.25) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        // Penalty box edge: rows 6-7 for HOME, rows 1-2 for AWAY (≈16.5m deep)
        double penaltyBoxEdgeRow = homeAttacking ? 6.0 : 2.0;
        double distFromEdge = Math.abs(foulPosition.getRow() - penaltyBoxEdgeRow);

        // If foul is clearly inside or outside (> 1 cell from edge), no VAR needed
        if (distFromEdge > PENALTY_BOX_EDGE_DISTANCE) {
            lastVARDecision = "NO_REVIEW";
            return true;
        }

        // Borderline — VAR reviews
        // ~30% chance of overturn if called penalty near edge
        double overturnChance = wasCalledPenalty ? 0.30 : 0.20;
        boolean overturned = random.nextDouble() < overturnChance;

        lastVARDecision = overturned ? "PENALTY_OVERTURNED" : "PENALTY_CONFIRMED";
        return !overturned;
    }

    /**
     * VAR check for a yellow card.
     * Reviews whether the tackle deserved a red card (upgrades) or no card (downgrades).
     *
     * @param defender the player who committed the foul
     * @return "UPGRADE_TO_RED", "DOWNGRADE_TO_NONE", or "CONFIRMED"
     */
    public String checkYellowCard(Player defender) {
        lastVARDecision = "NONE";

        // VAR only reviews ~10% of yellow cards
        if (state.getRandom().nextDouble() > 0.10) {
            lastVARDecision = "NO_REVIEW";
            return "CONFIRMED";
        }

        // ~8% chance of upgrading yellow to red (dangerous tackle)
        double upgradeChance = 0.08;
        if (state.getRandom().nextDouble() < upgradeChance) {
            lastVARDecision = "YELLOW_UPGRADED_TO_RED";
            return "UPGRADE_TO_RED";
        }

        // ~12% chance of downgrading yellow to no card (VAR says not a foul)
        double downgradeChance = 0.12;
        if (state.getRandom().nextDouble() < downgradeChance) {
            lastVARDecision = "YELLOW_DOWNGRADED";
            return "DOWNGRADE_TO_NONE";
        }

        lastVARDecision = "YELLOW_CONFIRMED";
        return "CONFIRMED";
    }

    /**
     * Log the VAR decision.
     */
    public void logVARDecision(String incidentType, String detail) {
        if ("NONE".equals(lastVARDecision) || "NO_REVIEW".equals(lastVARDecision)) return;
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                null, "VAR_" + lastVARDecision,
                "VAR " + lastVARDecision + " — " + incidentType + ": " + detail);
    }

    /**
     * Record a VAR DECISION event with an explicit, well-formed event type
     * (e.g. "VAR_OFFSIDE_CONFIRMED") and set lastVARDecision so the viewer /
     * reporter can render a CONFIRMED / OVERTURNED overlay.
     */
    public void recordVARDecision(String eventType, String detail) {
        this.lastVARDecision = eventType;
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                null, eventType,
                "VAR " + eventType + " — " + detail);
    }

    /**
     * Like {@link #recordVARDecision} but stamps the event with an explicit tick
     * so the viewer can render the review as taking time before the verdict.
     */
    public void recordVARDecisionAtTick(String eventType, String detail, long tick) {
        this.lastVARDecision = eventType;
        recorder.appendEvent(tick, state.getRound(),
                null, eventType,
                "VAR " + eventType + " — " + detail);
    }

    /**
     * Log that a VAR review is in progress.
     */
    public void logVARReviewStarted(String team, String reviewType) {
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                null, "VAR_IN_PROGRESS",
                "VAR IN PROGRESS — " + team + " — " + reviewType);
    }
}
