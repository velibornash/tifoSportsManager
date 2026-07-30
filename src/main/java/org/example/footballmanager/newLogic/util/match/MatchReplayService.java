package org.example.footballmanager.newLogic.util.match;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.repository.MatchEventRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchReplayService {

    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public String saveMatchEvents(Match match) {
        List<MatchEvent> events = matchEventRepository.findByMatch(match);
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