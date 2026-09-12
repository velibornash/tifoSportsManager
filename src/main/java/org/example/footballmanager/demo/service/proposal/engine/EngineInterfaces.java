package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;

/** Core engine interfaces - each engine has a single responsibility. */
public interface EngineInterfaces {

    /** Decision engine - only decides what action the carrier should take. */
    interface DecisionEngine {
        DecisionOption decide(MatchState state);
    }

    /** Execution engine - only executes the chosen action. */
    interface ActionExecutor {
        void execute(MatchState state, DecisionOption decision);
    }

    /** Movement engine - only moves players toward targets. */
    interface MovementEngine {
        void moveAllTowardTargets(MatchState state);
    }

    /** Ball physics engine - only handles ball physics and collisions. */
    interface BallPhysicsEngine {
        void moveBall(MatchState state);
        String checkOutOfBounds(Ball ball);
    }

    /** Duel engine - only resolves player duels. */
    interface DuelEngine {
        void update(MatchState state);
        boolean resolve(MatchState state);
    }

    /** Rules engine - only checks football rules (offside, etc.). */
    interface FootballRules {
        boolean isOffside(Player passer, Player receiver, MatchState state);
    }

    /** Restart engine - only handles match restarts. */
    interface RestartManager {
        void executeRestart(MatchState state, String restartType);
    }
}