package org.example.footballmanager.newLogic.util.events;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.engine.MatchMetrics;

public class MatchEventBus {

    private final List<Consumer<MatchEvent>> subscribers = new ArrayList<>();
    private final MatchMetrics metrics;

    public MatchEventBus() {
        this.metrics = new MatchMetrics();
    }

    public void subscribe(Consumer<MatchEvent> handler) {
        subscribers.add(handler);
    }

    public void publish(MatchEvent event) {
        for (Consumer<MatchEvent> handler : subscribers) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                // Don't let one subscriber break others
            }
        }
    }

    public MatchMetrics getMetrics() {
        return metrics;
    }

    public void reset() {
        subscribers.clear();
    }
}
