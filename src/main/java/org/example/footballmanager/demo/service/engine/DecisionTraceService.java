package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.DecisionTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision Trace / Observability Service — corePrinciples Sections 29-30.
 *
 * "Important decisions should generate structured debug information."
 * "Tracing must be configurable so that production simulation does not require verbose debug output."
 *
 * Collects decision traces for debugging and analysis.
 */
public class DecisionTraceService {

    private final List<DecisionTrace> traces = new ArrayList<>();
    private boolean enabled = false;
    private static final int MAX_TRACES = 1000;

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    /** Record a decision trace. Only records if tracing is enabled. */
    public void record(DecisionTrace trace) {
        if (!enabled) return;
        if (traces.size() >= MAX_TRACES) {
            traces.remove(0); // drop oldest
        }
        traces.add(trace);
    }

    /** Get all recorded traces. */
    public List<DecisionTrace> getTraces() {
        return List.copyOf(traces);
    }

    /** Get traces for a specific player. */
    public List<DecisionTrace> getTracesForPlayer(String playerLabel) {
        return traces.stream()
                .filter(t -> t.playerLabel().equals(playerLabel))
                .toList();
    }

    /** Get traces for a specific tick range. */
    public List<DecisionTrace> getTracesInRange(int fromTick, int toTick) {
        return traces.stream()
                .filter(t -> t.tick() >= fromTick && t.tick() <= toTick)
                .toList();
    }

    /** Clear all traces. */
    public void clear() { traces.clear(); }

    /** Print all traces as debug output. */
    public String dumpTraces() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Decision Traces (").append(traces.size()).append(" total) ===\n");
        for (DecisionTrace t : traces) {
            sb.append(t.toDebugString()).append("\n");
        }
        return sb.toString();
    }
}
