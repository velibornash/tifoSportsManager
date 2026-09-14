package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;

/**
 * Core engine interfaces — each engine has a single responsibility.
 *
 * All engines are stateless services (no mutable instance state) and receive
 * MatchState on every call.  MatchState is the single source of truth.
 */
public interface EngineInterfaces {

    /** Decision engine — decides what action the carrier should take. */
    interface DecisionEngine {
        DecisionOption decide(MatchState state);
    }

    /** Execution engine — executes the chosen action. */
    interface ActionExecutor {
        void execute(MatchState state, DecisionOption decision);
    }

    /** Movement engine — moves players toward their tactical targets. */
    interface MovementEngine {
        void moveAllTowardTargets(MatchState state);
    }

    /** Ball physics engine — handles ball physics and collisions. */
    interface BallPhysicsEngine {
        void moveBall(MatchState state);
        String checkOutOfBounds(Ball ball);
    }

    /** Duel engine — detects and resolves player duels. */
    interface DuelEngine {
        void update(MatchState state);
        boolean resolve(MatchState state);
    }

    /** Football rules — checks offside, fouls, cards, VAR review. */
    interface FootballRules {
        boolean isOffside(Player passer, Player receiver, MatchState state);
    }

    /** Restart engine — handles match restarts (kickoff, corner, throw-in, etc.). */
    interface RestartManager {
        void executeRestart(MatchState state, String restartType);
    }

    // --- NEW PLACEHOLDER INTERFACES (2026-09-14) ---
    // Concrete implementations are empty stubs — logic to be filled per
    // PROPOSAL_PROGRESS.md plan phases 8.6/8.4/8.5.

    /** VAR — video assistant referee: reviews offside, goal, red card, penalty. */
    interface VARService {
        boolean checkOffside(Player receiver, Position passOrigin, MatchState state);
        boolean checkGoal(String scoringTeam, Position goalPosition);
        boolean checkRedCard(Player defender, boolean isSecondYellow);
        boolean checkPenalty(Position foulPosition, boolean homeAttacking);
        String checkYellowCard(Player defender);
        void logVARDecision(String incidentType, String detail);
        void recordVARDecision(String eventType, String detail);
        void logVARReviewStarted(String team, String reviewType);
    }

    /** Discipline — foul detection, card issuance, VAR integration. */
    interface DisciplineService {
        record DisciplineResult(
                boolean foul,
                boolean yellowCard,
                boolean redCard,
                boolean penalty,
                boolean freeKick,
                String description
        ) {}

        DisciplineResult evaluateFoul(MatchState state);
    }

    /** Offside — continuous tracking + per-pass check + retreat logic. */
    interface OffsideService {
        void trackOffsidePositions(MatchState state);
        record OffsideResult(boolean confirmed, boolean wasChecked) {}
        OffsideResult checkOffside(Player receiver, Position passOrigin, MatchState state);
        void resolvePendingVAROffside(MatchState state);
    }

    /**
     * Threat override — three behaviors that modify tactical targets:
     * <ol>
     *   <li><b>TYPE A</b> — press carrier with ball anywhere on the pitch
     *       (enter duel on contact, especially dangerous in own third).</li>
     *   <li><b>TYPE B</b> — approach dangerous opponent WITHOUT ball in own
     *       defensive zone (anticipate pass → duel if ball comes).</li>
     *   <li><b>TYPE C</b> — offside retreat: attacker in offside position for
     *       N consecutive ticks pulls back toward own goal until onside,
     *       then resumes normal tactical positioning.</li>
     * </ol>
     * Overrides change the TARGET, never the speed — all players are
     * pace-capped at all times (no sprint boosts).
     */
    interface ThreatOverrideEngine {
        void evaluate(MatchState state);
    }
}