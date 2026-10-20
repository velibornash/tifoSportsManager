package org.example.footballmanager.demo.swingUIDemo;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only in-memory timeline for the demo. UI/log consumers may project
 * this data differently; the simulation does not depend on printed messages.
 */
public final class SimulationEventStore {
    private final List<StoredSimulationEvent> events = new ArrayList<>();
    private long nextSequence = 1;

    public synchronized StoredSimulationEvent append(SimulationEvent event) {
        StoredSimulationEvent stored = new StoredSimulationEvent(nextSequence++, event);
        events.add(stored);
        return stored;
    }

    public synchronized List<StoredSimulationEvent> snapshot() {
        return List.copyOf(events);
    }

    public synchronized int size() {
        return events.size();
    }

    public record StoredSimulationEvent(long sequence, SimulationEvent event) {}
}
