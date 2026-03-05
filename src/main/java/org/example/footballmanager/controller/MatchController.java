package org.example.footballmanager.controller;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.MatchEventDTO;
import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEndedEvent;
import org.example.footballmanager.model.event.PenaltyEvent;
import org.example.footballmanager.model.event.ShotOnTargetEvent;
import org.example.footballmanager.model.event.VARReviewEvent;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.MatchDetailService;
import org.example.footballmanager.old.oldService.MatchService;
import org.example.footballmanager.util.players.PlayerFactory;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchRepository matchRepository;
    private final MatchDetailService matchDetailService;
    private final MatchEventRepository matchEventRepository;
    private final MatchEventMapper matchEventMapper;
    @Autowired
    public MatchController(
            MatchRepository matchRepository,
            MatchDetailService matchDetailService,
            MatchEventRepository matchEventRepository,
            MatchEventMapper matchEventMapper
    ) {
        this.matchRepository = matchRepository;
        this.matchDetailService = matchDetailService;
        this.matchEventRepository = matchEventRepository;
        this.matchEventMapper = matchEventMapper;
    }


    @GetMapping("/{matchId}")
    public ResponseEntity<MatchDTO> getMatch(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(MatchDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    @GetMapping("/{matchId}/detail")
    public ResponseEntity<List<MatchEventFlatDTO>> getMatchDetail(@PathVariable Long matchId) {
        try {
            List<MatchEventFlatDTO> events = matchDetailService.getMatchEventsFlat(matchId);
            return ResponseEntity.ok(events);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{matchId}/key-events")
    public ResponseEntity<List<MatchEventDTO>> getKeyEvents(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> matchEventRepository.findByMatch(match).stream()
                        .filter(e -> e instanceof GoalEvent
                                || e instanceof PenaltyEvent
                                || e instanceof VARReviewEvent
                                || e instanceof ShotOnTargetEvent
                                || e instanceof MatchEndedEvent)
                        .sorted((a, b) -> {
                            int byMinute = Integer.compare(a.getMinute(), b.getMinute());
                            if (byMinute != 0) return byMinute;
                            if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                                int byCreated = a.getCreatedAt().compareTo(b.getCreatedAt());
                                if (byCreated != 0) return byCreated;
                            }
                            Long left = a.getId() != null ? a.getId() : Long.MAX_VALUE;
                            Long right = b.getId() != null ? b.getId() : Long.MAX_VALUE;
                            return Long.compare(left, right);
                        })
                        .map(matchEventMapper::toDto)
                        .toList())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
