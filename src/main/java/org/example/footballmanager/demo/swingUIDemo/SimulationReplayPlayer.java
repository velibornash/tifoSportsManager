package org.example.footballmanager.demo.swingUIDemo;

import java.util.List;

/** Cursor over saved replay frames; contains no simulation or random logic. */
public final class SimulationReplayPlayer {
    private List<SimulationSnapshot> frames = List.of();
    private int cursor;

    public void load(List<SimulationSnapshot> frames) {
        this.frames = List.copyOf(frames);
        this.cursor = 0;
    }

    public boolean hasFrame() { return cursor < frames.size(); }
    public SimulationSnapshot current() {
        return hasFrame() ? frames.get(cursor) : null;
    }
    public SimulationSnapshot next() {
        if (!hasFrame()) return null;
        return frames.get(cursor++);
    }
    public int size() { return frames.size(); }
    public int position() { return cursor; }
}
