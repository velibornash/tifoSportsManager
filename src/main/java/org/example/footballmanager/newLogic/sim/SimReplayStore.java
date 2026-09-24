package org.example.footballmanager.newLogic.sim;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store for finished sim match recordings served to the proposal
 * viewer (preview/service/ui). Mirrors the MatchStore pattern for the newLogic
 * engine: replays live for the app session and are not persisted.
 */
@Component
public class SimReplayStore {

    private final Map<Long, Map<String, Object>> replays = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(1);

    public long store(Map<String, Object> view) {
        long id = counter.getAndIncrement();
        view.put("matchId", id);
        replays.put(id, view);
        return id;
    }

    public Map<String, Object> get(long id) {
        return replays.get(id);
    }
}