package org.example.footballmanager.cleanSheet;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.cleanSheet.model.*;
import org.example.footballmanager.cleanSheet.state.CleanSheetGameState;
import org.example.footballmanager.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cs")
@RequiredArgsConstructor
public class CleanSheetController {

    private final CleanSheetService cleanSheetService;

    @PostMapping("/start")
    public ResponseEntity<?> startGame(@AuthenticationPrincipal User user) {
        if (user == null || user.getTeam() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
        CleanSheetGameState state = cleanSheetService.startNewGame(user.getId(), user.getTeam());
        return ResponseEntity.ok(buildStateResponse(state));
    }

    @GetMapping("/state")
    public ResponseEntity<?> getState(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        CleanSheetGameState state = cleanSheetService.getState(user.getId());
        if (state == null) {
            return ResponseEntity.ok(Map.of("active", false));
        }
        return ResponseEntity.ok(buildStateResponse(state));
    }

    @PostMapping("/next-round")
    public ResponseEntity<?> nextRound(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        try {
            Map<String, Object> result = cleanSheetService.advanceRound(user.getId());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/table")
    public ResponseEntity<List<CSTableEntry>> getTable(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.getTable(user.getId()));
    }

    @GetMapping("/players")
    public ResponseEntity<List<CSPlayer>> getPlayers(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.getPlayers(user.getId()));
    }

    @GetMapping("/schedule")
    public ResponseEntity<List<CSFixture>> getSchedule(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.getSchedule(user.getId()));
    }

    @GetMapping("/inbox")
    public ResponseEntity<List<CSInboxMessage>> getInbox(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.getInbox(user.getId()));
    }

    @PutMapping("/tactics")
    public ResponseEntity<CSTactics> changeTactics(@AuthenticationPrincipal User user,
                                                    @RequestParam(required = false) String formation,
                                                    @RequestParam(required = false) String style) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.changeTactics(user.getId(), formation, style));
    }

    @GetMapping("/team/{teamId}")
    public ResponseEntity<?> getTeamInfo(@AuthenticationPrincipal User user,
                                         @PathVariable Long teamId) {
        if (user == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(cleanSheetService.getTeamInfo(user.getId(), teamId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/top-scorers")
    public ResponseEntity<?> getTopScorers(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.getTopScorers(user.getId()));
    }

    @GetMapping("/top-assists")
    public ResponseEntity<?> getTopAssists(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.getTopAssists(user.getId()));
    }

    private Map<String, Object> buildStateResponse(CleanSheetGameState state) {
        return Map.of(
                "active", true,
                "userTeam", state.getUserTeam(),
                "roster", state.getRoster(),
                "leagueTable", state.getLeagueTable(),
                "tactics", state.getTactics(),
                "currentRound", state.getCurrentRound(),
                "totalRounds", state.getTotalRounds(),
                "seasonYear", state.getSeasonYear(),
                "inbox", state.getInbox()
        );
    }
}
