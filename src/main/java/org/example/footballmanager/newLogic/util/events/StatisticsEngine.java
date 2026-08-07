package org.example.footballmanager.newLogic.util.events;

import java.util.function.Consumer;
import org.example.footballmanager.newLogic.engine.MatchMetrics;
import org.example.footballmanager.newLogic.model.event.*;

public class StatisticsEngine implements Consumer<MatchEvent> {

    private final MatchMetrics metrics = new MatchMetrics();

    @Override
    public void accept(MatchEvent event) {
        if (event instanceof PassEvent) {
            metrics.onPass();
        } else if (event instanceof ShotEvent || event instanceof ShotSavedEvent
                || event instanceof ShotMissedEvent || event instanceof ShotBlockedEvent) {
            metrics.onShot();
            if (event instanceof ShotEvent && ((ShotEvent) event).onTarget()) {
                metrics.onShotOnTarget();
            }
        } else if (event instanceof GoalEvent) {
            metrics.onGoal();
        } else if (event instanceof FoulEvent) {
            metrics.onFoul();
        } else if (event instanceof CardEvent) {
            if (((CardEvent) event).cardType() == CardEvent.CardType.YELLOW) {
                metrics.onFoul();
            }
        } else if (event instanceof SetPieceEvent spe) {
            switch (spe.setPieceType()) {
                case THROW_IN -> metrics.onThrowIn();
                case CORNER -> metrics.onCorner();
                case GOAL_KICK -> metrics.onGoalKick();
                default -> {}
            }
        } else if (event instanceof OffsideEvent) {
            metrics.onOffside();
        } else if (event instanceof ThroughBallEvent) {
            metrics.onThroughBall();
        } else if (event instanceof CrossEvent) {
            metrics.onCross();
        } else if (event instanceof DuelEvent || event instanceof TackleEvent) {
            metrics.onDuel();
        } else if (event instanceof ClearanceEvent) {
            metrics.onClearance();
        } else if (event instanceof DribbleEvent) {
            metrics.onDribble();
        } else if (event instanceof TackleFoulEvent) {
            metrics.onTackle();
        } else if (event instanceof PassInterceptedEvent) {
            metrics.onInterception();
        }
    }

    public MatchMetrics getMetrics() {
        return metrics;
    }

    public String getSummaryReport() {
        return metrics.toSummary();
    }
}
