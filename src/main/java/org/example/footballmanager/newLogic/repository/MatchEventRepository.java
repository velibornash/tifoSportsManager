package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class MatchEventRepository {
    private final Map<Long, List<MatchEvent>> store = new ConcurrentHashMap<>();

    public List<MatchEvent> findByMatch(Match match) {
        return store.getOrDefault(match.getId(), Collections.emptyList());
    }

    public <T extends MatchEvent> T save(T event) {
        return event;
    }

    public void deleteAll(List<MatchEvent> events) {
        if (!events.isEmpty()) {
            Long matchId = events.get(0).minute() >= 0 ? null : null;
        }
    }

    public void saveAll(List<MatchEvent> events) {
        events.forEach(this::save);
    }
}
