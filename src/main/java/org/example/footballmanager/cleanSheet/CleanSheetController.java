package org.example.footballmanager.cleanSheet;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.cleanSheet.model.*;
import org.example.footballmanager.cleanSheet.state.CleanSheetGameState;
	import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
	import org.example.footballmanager.repository.TeamRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cs")
@RequiredArgsConstructor
public class CleanSheetController {

    private final CleanSheetService cleanSheetService;
	    private final TeamRepository teamRepository;

    @PostMapping("/start")
    public ResponseEntity<?> startGame(@AuthenticationPrincipal User user) {
        if (user == null || user.getTeam() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
	        Team team = teamRepository.findById(user.getTeam().getId()).orElse(user.getTeam());
	        CleanSheetGameState state = cleanSheetService.startNewGame(user.getId(), team);
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

    @PostMapping("/reset")
    public ResponseEntity<?> resetGame(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        cleanSheetService.resetGame(user.getId());
        return ResponseEntity.ok(Map.of("ok", true));
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

    @PostMapping("/inbox/{index}/read")
    public ResponseEntity<?> markInboxRead(@AuthenticationPrincipal User user,
                                           @PathVariable int index) {
        if (user == null) return ResponseEntity.status(401).build();
        cleanSheetService.markInboxRead(user.getId(), index);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/tactics")
    public ResponseEntity<CSTactics> changeTactics(@AuthenticationPrincipal User user,
                                                    @RequestParam(required = false) String formation,
                                                    @RequestParam(required = false) String style) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.changeTactics(user.getId(), formation, style));
    }

    @PutMapping("/tactics/starters")
    public ResponseEntity<CSTactics> changeStarters(@AuthenticationPrincipal User user,
                                                    @RequestBody Map<String, List<Long>> payload) {
        if (user == null) return ResponseEntity.status(401).build();
        List<Long> starterIds = payload.getOrDefault("starterIds", List.of());
        List<Long> benchIds = payload.getOrDefault("benchIds", List.of());
        return ResponseEntity.ok(cleanSheetService.changeLineup(user.getId(), starterIds, benchIds));
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

    @GetMapping("/transfers")
    public ResponseEntity<?> getTransfers(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.getTransferCentre(user.getId()));
    }

    @PostMapping("/transfers/list/{playerId}")
    public ResponseEntity<?> listTransfer(@AuthenticationPrincipal User user,
                                          @PathVariable Long playerId,
                                          @RequestBody(required = false) Map<String, Object> payload) {
        if (user == null) return ResponseEntity.status(401).build();
        double askingPrice = payload != null && payload.get("askingPrice") instanceof Number number
                ? number.doubleValue()
                : 0.0;
        return ResponseEntity.ok(cleanSheetService.listPlayerForTransfer(user.getId(), playerId, askingPrice));
    }

    @DeleteMapping("/transfers/list/{playerId}")
    public ResponseEntity<?> removeTransfer(@AuthenticationPrincipal User user,
                                            @PathVariable Long playerId) {
        if (user == null) return ResponseEntity.status(401).build();
        cleanSheetService.removePlayerFromTransferList(user.getId(), playerId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/transfers/buy/{playerId}")
    public ResponseEntity<?> buyTransfer(@AuthenticationPrincipal User user,
                                         @PathVariable Long playerId) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.buyListedPlayer(user.getId(), playerId));
    }

    @PostMapping("/transfers/bid/{playerId}")
    public ResponseEntity<?> bidTransfer(@AuthenticationPrincipal User user,
                                         @PathVariable Long playerId,
                                         @RequestBody(required = false) Map<String, Object> payload) {
        if (user == null) return ResponseEntity.status(401).build();
        double offer = payload != null && payload.get("offer") instanceof Number number
                ? number.doubleValue()
                : 0.0;
        return ResponseEntity.ok(cleanSheetService.bidForPlayer(user.getId(), playerId, offer));
    }

    @PostMapping("/transfers/accept/{playerId}")
    public ResponseEntity<?> acceptOffer(@AuthenticationPrincipal User user,
                                         @PathVariable Long playerId) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.acceptIncomingOffer(user.getId(), playerId));
    }

    @PostMapping("/transfers/reject/{playerId}")
    public ResponseEntity<?> rejectOffer(@AuthenticationPrincipal User user,
                                         @PathVariable Long playerId) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.rejectIncomingOffer(user.getId(), playerId));
    }

    private Map<String, Object> buildStateResponse(CleanSheetGameState state) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("active", true);
        response.put("userTeam", state.getUserTeam());
        response.put("roster", state.getRoster());
        response.put("leagueTable", state.getLeagueTable());
        response.put("leagueName", state.getLeagueName());
        response.put("tactics", state.getTactics());
        response.put("currentRound", state.getCurrentRound());
        response.put("totalRounds", state.getTotalRounds());
        response.put("seasonYear", state.getSeasonYear());
        response.put("schedule", state.getSchedule());
        response.put("matchHistory", state.getMatchHistory());
        response.put("inbox", state.getInbox());
        response.put("seasonHistory", state.getSeasonHistory());
        response.put("clubMood", state.getClubMood());
        response.put("internationalCompetitionName", state.getInternationalCompetitionName());
        response.put("internationalMatchday", state.getInternationalMatchday());
        response.put("internationalTable", state.getInternationalTable());
        response.put("internationalWindows", state.getInternationalWindows());
        response.put("transferMarket", state.getTransferMarket());
        response.put("affiliateClubName", state.getAffiliateClubName());
        response.put("affiliateClubCountry", state.getAffiliateClubCountry());
        response.put("affiliateClubNote", state.getAffiliateClubNote());
        response.put("lastRoundIncome", state.getLastRoundIncome());
        response.put("lastRoundExpenses", state.getLastRoundExpenses());
        response.put("weeklyWageBill", state.getWeeklyWageBill());
        response.put("boardObjectiveTitle", state.getBoardObjectiveTitle());
        response.put("boardObjectiveText", state.getBoardObjectiveText());
        response.put("boardReviewTitle", state.getBoardReviewTitle());
        response.put("boardReviewText", state.getBoardReviewText());
        response.put("notableNews", state.getNotableNews());
        return response;
    }
}
