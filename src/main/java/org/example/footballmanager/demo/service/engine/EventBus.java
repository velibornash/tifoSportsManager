package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.GameContext;
import org.example.footballmanager.demo.service.model.MatchPhase;
import org.example.footballmanager.demo.service.recording.MatchEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event Bus — corePrinciples Section 37.
 *
 * "No single service should own all responsibilities."
 *
 * Decouples event generation from event consumption.
 * Services publish events; statistics, replay, and reporting subscribe independently.
 */
public class EventBus {

    public interface EventListener {
        void onEvent(MatchEvent event);
    }

    public interface PhaseChangeListener {
        void onPhaseChange(MatchPhase oldPhase, MatchPhase newPhase);
    }

    public interface GoalListener {
        void onGoal(String team, int minute, String scorerLabel);
    }

    public interface PossessionChangeListener {
        void onPossessionChange(String oldTeam, String newTeam, int tick);
    }

    private final List<EventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final List<PhaseChangeListener> phaseListeners = new CopyOnWriteArrayList<>();
    private final List<GoalListener> goalListeners = new CopyOnWriteArrayList<>();
    private final List<PossessionChangeListener> possessionListeners = new CopyOnWriteArrayList<>();

    public void addEventListener(EventListener listener) { eventListeners.add(listener); }
    public void removeEventListener(EventListener listener) { eventListeners.remove(listener); }
    public void addPhaseListener(PhaseChangeListener listener) { phaseListeners.add(listener); }
    public void removePhaseListener(PhaseChangeListener listener) { phaseListeners.remove(listener); }
    public void addGoalListener(GoalListener listener) { goalListeners.add(listener); }
    public void removeGoalListener(GoalListener listener) { goalListeners.remove(listener); }
    public void addPossessionListener(PossessionChangeListener listener) { possessionListeners.add(listener); }
    public void removePossessionListener(PossessionChangeListener listener) { possessionListeners.remove(listener); }

    /** Publish a match event to all listeners. */
    public void publish(MatchEvent event) {
        for (EventListener listener : eventListeners) {
            listener.onEvent(event);
        }
    }

    /** Publish a phase change. */
    public void publishPhaseChange(MatchPhase oldPhase, MatchPhase newPhase) {
        for (PhaseChangeListener listener : phaseListeners) {
            listener.onPhaseChange(oldPhase, newPhase);
        }
    }

    /** Publish a goal event. */
    public void publishGoal(String team, int minute, String scorerLabel) {
        for (GoalListener listener : goalListeners) {
            listener.onGoal(team, minute, scorerLabel);
        }
    }

    /** Publish a possession change. */
    public void publishPossessionChange(String oldTeam, String newTeam, int tick) {
        for (PossessionChangeListener listener : possessionListeners) {
            listener.onPossessionChange(oldTeam, newTeam, tick);
        }
    }

    /** Remove all listeners. */
    public void clear() {
        eventListeners.clear();
        phaseListeners.clear();
        goalListeners.clear();
        possessionListeners.clear();
    }
}
