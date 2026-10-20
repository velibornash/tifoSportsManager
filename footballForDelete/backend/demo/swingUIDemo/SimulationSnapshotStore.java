package org.example.footballmanager.demo.swingUIDemo;

import java.util.ArrayList;
import java.util.List;

/** Append-only per-tick scene snapshots for replay; independent of UI logs. */
public final class SimulationSnapshotStore {
    private final List<SimulationSnapshot> snapshots = new ArrayList<>();

    public synchronized void append(SimulationSnapshot snapshot) {
        snapshots.add(snapshot);
    }

    public synchronized List<SimulationSnapshot> snapshot() {
        return List.copyOf(snapshots);
    }

    public synchronized int size() {
        return snapshots.size();
    }
}
