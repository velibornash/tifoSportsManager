package org.example.footballmanager.newLogic.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CurrentRoundSimulationStateService {

    private final AtomicReference<Map<String, Object>> feedSnapshot = new AtomicReference<>(defaultFeed());
    private final AtomicReference<Map<String, Object>> prepareSnapshot = new AtomicReference<>(idleSnapshot("prepare"));
    private final AtomicReference<Map<String, Object>> roundSimulationSnapshot = new AtomicReference<>(idleSnapshot("simulate-all"));
    private final AtomicReference<Map<String, Object>> advanceSnapshot = new AtomicReference<>(idleSnapshot("advance"));

    public Map<String, Object> getFeedSnapshot() {
        return copy(feedSnapshot.get());
    }

    public void setFeedSnapshot(Map<String, Object> payload) {
        feedSnapshot.set(copy(payload));
    }

    public Map<String, Object> getPrepareSnapshot() {
        return copy(prepareSnapshot.get());
    }

    public void setPrepareSnapshot(Map<String, Object> payload) {
        prepareSnapshot.set(copy(payload));
    }

    public Map<String, Object> getRoundSimulationSnapshot() {
        return copy(roundSimulationSnapshot.get());
    }

    public void setRoundSimulationSnapshot(Map<String, Object> payload) {
        roundSimulationSnapshot.set(copy(payload));
    }

    public Map<String, Object> getAdvanceSnapshot() {
        return copy(advanceSnapshot.get());
    }

    public void setAdvanceSnapshot(Map<String, Object> payload) {
        advanceSnapshot.set(copy(payload));
    }

    private static Map<String, Object> defaultFeed() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("currentWeek", 0);
        payload.put("userLeague", "League");
        payload.put("leagues", java.util.List.of());
        return payload;
    }

    private static Map<String, Object> idleSnapshot(String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "idle");
        payload.put("action", action);
        payload.put("message", "No active job.");
        return payload;
    }

    private static Map<String, Object> copy(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }
}
