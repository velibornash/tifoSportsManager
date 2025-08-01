package org.example.footballmanager.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.repository.MatchRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchReplayService {

    private final MatchRepository matchRepository;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public String saveMatchEvents(Match match) {
        List<MatchEvent> events = match.getAllMatchEvents();
        String json = objectMapper.writeValueAsString(events);
        match.setEventJson(json);
        matchRepository.save(match);
        return json;
    }

    @SneakyThrows
    public List<MatchEvent> loadMatchEvents(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        String json = match.getEventJson();
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<List<MatchEvent>>() {});
    }
}