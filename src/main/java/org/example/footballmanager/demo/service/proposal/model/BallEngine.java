package org.example.footballmanager.demo.service.proposal.model;

/** Minimal interface for ball engine operations needed by MatchState/ActionExecutor. */
public interface BallEngine {
    void launch(Ball ball, Position origin, Position aim, double speed, boolean airborne, double spin);
}