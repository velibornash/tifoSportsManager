package org.example.footballmanager.controller;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.LineupRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.MatchDetailService;
import org.example.footballmanager.old.oldService.MatchService;
import org.example.footballmanager.util.players.PlayerFactory;
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
    private final MatchDetailService  matchDetailService;
    @Autowired
    public MatchController( MatchRepository matchRepository, MatchDetailService matchDetailService) {
        this.matchRepository = matchRepository;
        this.matchDetailService = matchDetailService;
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
}