package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.*;

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
                String description,
                String varDecision
        ) {
            public DisciplineResult(boolean foul, boolean yellowCard, boolean redCard,
                                    boolean penalty, boolean freeKick, String description) {
                this(foul, yellowCard, redCard, penalty, freeKick, description, "NONE");
            }
        }

        DisciplineResult evaluateFoul(MatchState state);
    }

    /** Offside — continuous tracking + per-pass check + retreat logic. */
    interface OffsideService {
        void trackOffsidePositions(MatchState state);
        record OffsideResult(boolean confirmed, boolean wasChecked) {}
        OffsideResult checkOffside(Player receiver, Position passOrigin, MatchState state);

        /**
         * Whether a decision is a delivery that puts the ball at a team-mate's feet, and can
         * therefore be offside.
         *
         * <p>An ordinary pass, a cross and a centre are the same thing to the offside law: the ball
         * is played to a team-mate, and if he is in an offside position when it is played, the flag
         * goes up. <b>A cross used to skip the check entirely</b>, so a cross to a runner beyond the
         * second-to-last defender was a normal reception — he could shoot and score from it.
         *
         * <p>A through ball is not here: the decision engine already refuses any mate beyond the
         * offside line before the option is ever built, so a check would never fire for it.
         *
         * @param type the type of the decision
         * @return true when the decision is a delivery that can be offside
         */
        boolean isOffsideCheckedDelivery(ActionType type);

        /**
         * Whether a held offside check is waiting on this player.
         *
         * <p>A pass inside the marginal band does not bring a whistle, so the check is held. If the
         * receiver then scores, the goal is the thing that makes the position worth reviewing — and the
         * goal has to be settled <b>before</b> it is counted, or the scorer is both credited and flagged.
         *
         * <p>Until this existed the held check was cancelled a tick after it was created — nothing ever
         * said a goal had been scored, because {@code setOffsideLedToGoal(true)} was never called from
         * anywhere — so the whole deferred branch was unreachable and a goal from a marginally offside
         * position stood unquestioned.
         *
         * @param shooter the player credited with the goal
         * @param state the match state
         * @return true when this player is the one the held check is about
         */
        boolean isDeferredOffsideScorer(Player shooter, MatchState state);

        /**
         * Resolve a held offside check.
         *
         * @param state the match state
         * @return true when an offside was confirmed — in which case a goal must not count, and the
         *     indirect free kick has been set up
         */
        boolean resolvePendingVAROffside(MatchState state);
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