package org.example.footballtextmanager;

import lombok.RequiredArgsConstructor;
import org.example.footballtextmanager.model.*;
import org.example.footballtextmanager.state.CleanSheetGameState;
import org.example.footballtextmanager.model.CTeam;
import org.example.commonmanager.model.User;
import org.example.footballtextmanager.repository.CSTeamRepository;
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
	    private final CSTeamRepository teamRepository;

    @PostMapping("/start")
    public ResponseEntity<?> startGame(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        // The text mode reads User.tifoCTeam. User.cTeam is the *other* text mode's column and was
        // what this guard used to check, so a user with only tifoCTeam set passed the guard and then
        // dereferenced a null tifoCTeam on the next line. Both are set by DatabaseInitializer, which
        // is why it never fired in practice — it is a guard that guarded the wrong field.
        CTeam csTeam = user.getTifoCTeam() != null ? user.getTifoCTeam() : user.getCTeam();
        if (csTeam == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No text-manager club is attached to this account yet. "
                            + "Seed the text world, or set the club on the account."));
        }
        // Re-read through the repository so the returned entity is a managed, fully populated row.
        // A detached or transient reference has a null id, and findById(null) throws rather than
        // returning empty.
        if (csTeam.getId() != null) {
            csTeam = teamRepository.findById(csTeam.getId()).orElse(csTeam);
        }
        CleanSheetGameState state = cleanSheetService.startNewGame(user.getId(), csTeam);
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

    /**
     * Error body for a failed text-mode request.
     *
     * <p>This exists because {@code Map.of("error", e.getMessage())} threw a
     * {@link NullPointerException} of its own whenever the message was null — which is exactly
     * what a bare {@code new RuntimeException()} produces. The original failure was replaced by
     * a 500 whose body read {@code null}, so the endpoint reported "something went wrong" about a
     * problem it had just swallowed. A null message now falls back to the exception type, which
     * at least names what failed.
     */
    private static ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @PostMapping("/next-round")
    public ResponseEntity<?> nextRound(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        try {
            Map<String, Object> result = cleanSheetService.advanceRound(user.getId());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return badRequest(e);
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

    @PostMapping("/inbox/read-all")
    public ResponseEntity<?> markAllInboxRead(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        cleanSheetService.markAllInboxRead(user.getId());
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

    @PutMapping("/training/focus")
    public ResponseEntity<?> setTrainingFocus(@AuthenticationPrincipal User user,
                                             @RequestParam(required = false) String focus) {
        if (user == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(cleanSheetService.setTrainingFocus(user.getId(), focus));
    }

    @GetMapping("/team/{teamId}")
    public ResponseEntity<?> getTeamInfo(@AuthenticationPrincipal User user,
                                         @PathVariable Long teamId) {
        if (user == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(cleanSheetService.getTeamInfo(user.getId(), teamId));
        } catch (RuntimeException e) {
            return badRequest(e);
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
        response.put("trainingFocus", state.getTrainingFocus());
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
