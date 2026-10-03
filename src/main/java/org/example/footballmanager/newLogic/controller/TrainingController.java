package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Training;
import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.dto.training.PlayerTrainingGraphPointDTO;
import org.example.footballmanager.newLogic.dto.training.PlayerTrainingReportDTO;
import org.example.footballmanager.newLogic.dto.training.TrainingSetupDTO;
import org.example.footballmanager.newLogic.dto.training.TrainingWeekReportDTO;
import org.example.footballmanager.newLogic.dto.training.TrainingWeekSummaryDTO;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TrainingRepository;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.service.PlusFeatureService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.model.TrainingIntensity;
import org.example.footballmanager.newLogic.service.TrainingIntensityService;
import org.example.footballmanager.newLogic.service.PlayerSkillProgressionService;
import org.example.footballmanager.newLogic.service.TrainingProgressionService;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.exception.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.example.footballmanager.newLogic.util.SortWhitelist;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

import java.util.List;
import java.util.Set;
import java.util.Optional;

@RestController
@RequestMapping("/training")
public class TrainingController {

    private final TrainingRepository trainingRepository;
    private final PlayerRepository playerRepository;
    private final PlayerSkillProgressionService progressionService;
    private final TrainingProgressionService trainingProgressionService;
    private final PlusFeatureService plusFeatures;
    private final TrainingIntensityService intensityService;
    private final SeasonService seasonService;


    public TrainingController(TrainingRepository trainingRepository, PlayerRepository playerRepository,
                             PlayerSkillProgressionService progressionService,
                             TrainingProgressionService trainingProgressionService,
                             PlusFeatureService plusFeatures,
                        TrainingIntensityService intensityService,
                             SeasonService seasonService) {
        this.plusFeatures = plusFeatures;
        this.intensityService = intensityService;
        this.seasonService = seasonService;
        this.trainingRepository = trainingRepository;
        this.playerRepository = playerRepository;
        this.progressionService = progressionService;
        this.trainingProgressionService = trainingProgressionService;
    }

    // Vraća sve treninge
    @GetMapping
    public List<Training> getAllTrainings(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "100") int size,
                                          @RequestParam(defaultValue = "id") String sortBy,
                                          @RequestParam(defaultValue = "asc") String direction) {
        Sort sort = SortWhitelist.of(sortBy, direction, "sortBy",
                Set.of("id", "name", "date"));
        return trainingRepository.findAll(PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)), sort))
                .getContent();
    }

    // Postavlja formaciju za igrača
    @PostMapping("/player/{playerId}/formation")
    public Training assignFormation(@PathVariable Long playerId, @RequestParam(required = false, defaultValue = "default") String formation) {
        Optional<Player> playerOpt = playerRepository.findById(playerId);
        if (playerOpt.isEmpty()) throw new RuntimeException("Igrač nije pronađen");

        Training training = trainingRepository.findByPlayerId(playerId)
                .orElse(new Training());

        training.setPlayer(playerOpt.get());
        training.setFormation(formation);
        training.setAdvanced(false);

        return trainingRepository.save(training);
    }

    // Dodaje igrača u "advanced training"
    @PostMapping("/advanced/{playerId}")
    public Training assignAdvancedTraining(@PathVariable Long playerId, @RequestParam(required = false, defaultValue = "default") String formation) {
        Optional<Player> playerOpt = playerRepository.findById(playerId);
        if (playerOpt.isEmpty()) throw new RuntimeException("Igrač nije pronađen");

        Training training = trainingRepository.findByPlayerId(playerId)
                .orElse(new Training());

        training.setPlayer(playerOpt.get());
        training.setFormation(formation);
        training.setAdvanced(true);

        return trainingRepository.save(training);
    }

    // Uklanja igrača iz advanced training-a
    @DeleteMapping("/advanced/{playerId}")
    public void removeFromAdvancedTraining(@PathVariable Long playerId) {
        trainingRepository.findByPlayerId(playerId).ifPresent(training -> {
            training.setAdvanced(false);
            trainingRepository.save(training);
        });
    }

    // Vraća trening jednog igrača
    @GetMapping("/player/{playerId}")
    public Training getTrainingForPlayer(@PathVariable Long playerId) {
        return trainingRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new RuntimeException("Trening nije pronađen za igrača " + playerId));
    }

    /**
     * Trains one player and answers with the same DTO every other player reader in the game uses.
     *
     * <p><b>This trained and returned any player in the world.</b> The id was taken at face value, no club
     * was involved, and the raw {@code Player} went back out — carrying {@code talent}, {@code earnings},
     * the injury record, {@code personality} and the {@code skills} object. That is the disclosure P0-1a
     * closed on {@code /players/paged} and P0-1b found on {@code /players}, on a third surface nobody had
     * looked at.
     *
     * <p><b>The ownership rule already existed in this file.</b> {@code setIntensity}, forty lines below,
     * documents the refusal it makes: <i>"a player who does not play for this club is a 403, not a bad
     * request, because the request is well formed and the manager simply is not allowed to make it."</i> And
     * {@code plusFeatures} was already injected to make exactly that check. So the rule was written, and
     * applied to one route out of two.
     */
    @PostMapping("/train/{playerId}")
    public ResponseEntity<PlayerDTO> trainPlayer(@PathVariable Long playerId,
                                                 @AuthenticationPrincipal User principal) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND",
                        "Player not found: " + playerId));

        if (!mayTrain(principal, player)) {
            throw new AccessDeniedException("You can only train players at your own club.");
        }

        progressionService.trainPlayer(player);
        Player saved = playerRepository.save(player);
        Long viewerTeamId = plusFeatures.viewerTeamId(principal);
        return ResponseEntity.ok(PlayerDTO.from(saved, 0, null,
                plusFeatures.talentOrNull(saved, principal, viewerTeamId)));
    }

    /**
     * May this caller train this player?
     *
     * <p>The player's <b>club</b> is the thing checked, not the player's id — the id is in the path and is
     * therefore the caller's to choose. Fails closed: no principal, no club on the player, or a club the
     * caller does not run are all "not yours".
     *
     * <p>The owner is let through, because a fix that locks the owner out of his own game is worse than
     * the hole it closes.
     */
    private boolean mayTrain(User principal, Player player) {
        if (principal == null || player == null) {
            return false;
        }
        if (principal.getRole() != null && principal.getRole().name().equals("OWNER")) {
            return true;
        }
        return plusFeatures.isOwnPlayer(player, plusFeatures.viewerTeamId(principal));
    }

    /**
     * Trains every player in the world.
     *
     * <p><b>An administrator action, and an operator escape hatch.</b> It used to be reachable by any
     * logged-in manager: {@code findAll()} plus {@code saveAll()} over every player in the database —
     * roughly 300,000 rows at the scale this project targets — on a request thread.
     *
     * <p>It has <b>zero callers</b>: not in {@code static/js}, not in {@code src/main}, not in one test. The
     * game's training runs through day 4's {@code TrainingJob} and through
     * {@code POST /training/weekly/team/{teamId}/run}, which is what the training screen actually calls.
     *
     * <p><b>So it is guarded, not deleted — by the owner's decision</b>, and the deletion question is
     * recorded on the board rather than settled here. A role guard answers "who may"; it does not answer
     * "should this exist at all", and this is the most expensive request in the game with nothing to buy.
     */
    @PostMapping("/train-all")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'DEV', 'ADMIN')")
    public Map<String, Object> trainAllPlayers() {
        // **It reports; it does not return the world.**
        //
        // This used to return `List<Player>` — every player in the database, serialised. At the scale
        // this project targets that is roughly 300,000 entities with their positions and skills, so the
        // response was hundreds of megabytes of JSON that nobody asked for: the caller's only question
        // is "did it train them", and the log line below already answers it.
        //
        // <b>Deliberately not paged.</b> `findAll(Pageable)` with no sort has an undefined order, and
        // paging an unordered query can skip and repeat rows — which here would silently train some
        // players twice and others not at all, with a count at the end claiming success. Making this
        // safe needs a total order on the query first; that is the same hazard the recovery read has,
        // and it is recorded rather than solved here.
        List<Player> players = playerRepository.findAll();
        players.forEach(progressionService::trainPlayer);
        List<Player> saved = playerRepository.saveAll(players);
        return Map.of("trained", saved.size(), "action", "ALL_PLAYERS_TRAINED");
    }

    // --- New weekly training setup/report API ---
    @GetMapping("/setup/team/{teamId}")
    public TrainingSetupDTO getCurrentSetup(@PathVariable Long teamId) {
        return trainingProgressionService.getCurrentSetup(teamId);
    }

    @PutMapping("/setup/team/{teamId}")
    public TrainingSetupDTO saveCurrentSetup(@PathVariable Long teamId, @RequestBody TrainingSetupDTO setup) {
        return trainingProgressionService.saveCurrentSetup(teamId, setup);
    }

    /**
     * Trains the squad for the current week. Idempotent per (season, week): a second call in the
     * same week is rejected with 409 TRAINING_ALREADY_RUN, which is what stops the UI button from
     * being a free infinite-skill-point exploit.
     */
    /**
     * Sets or clears a player's intensity override for a week (Sprint 4.2).
     *
     * <p>Mirrors the focus endpoint deliberately, down to the refusal: a player who does not play for
     * this club is a 403, not a bad request, because the request is well formed and the manager simply
     * is not allowed to make it. An empty or absent {@code intensity} clears the override, which puts
     * the player back on his club's setting rather than removing him from training.
     */
    @org.springframework.web.bind.annotation.PutMapping("/weekly/team/{teamId}/intensity/{playerId}")
    public ResponseEntity<Map<String, Object>> setIntensity(@PathVariable Long teamId,
                                                            @PathVariable Long playerId,
                                                            @RequestParam(required = false) Integer season,
                                                            @RequestParam(required = false) Integer week,
                                                            @RequestBody IntensityRequest request) {
        int resolvedSeason = season != null ? season : currentSeason();
        int resolvedWeek = week != null ? week : currentWeek();

        String requested = request == null ? null : request.intensity();
        TrainingIntensity intensity = TrainingIntensity.byName(requested);
        if (intensity == null && requested != null && !requested.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_INTENSITY",
                    "'" + requested + "' is not an intensity. Use LIGHT, NORMAL or VERY_HARD.");
        }

        if (!intensityService.setOverride(teamId, playerId, resolvedSeason, resolvedWeek, intensity)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_PLAYER",
                    "That player does not play for this club, so his intensity cannot be set.");
        }
        return ResponseEntity.ok(Map.of(
                "playerId", playerId,
                "season", resolvedSeason,
                "week", resolvedWeek,
                "intensity", intensity == null ? "CLUB_DEFAULT" : intensity.name()));
    }

    public record IntensityRequest(String intensity) {
    }





    private int currentSeason() {
        return seasonService.getOrCreateClock().getCurrentSeason() == null
                ? 1 : seasonService.getOrCreateClock().getCurrentSeason();
    }

    private int currentWeek() {
        return seasonService.getOrCreateClock().getCurrentWeek() == null
                ? 1 : seasonService.getOrCreateClock().getCurrentWeek();
    }

    @PostMapping("/weekly/team/{teamId}/run")
    public TrainingWeekReportDTO runWeeklyTraining(@PathVariable Long teamId) {
        return trainingProgressionService.runWeeklyTraining(teamId);
    }

    @GetMapping("/weekly/team/{teamId}/reports")
    public List<TrainingWeekSummaryDTO> getReportSummaries(@PathVariable Long teamId) {
        return trainingProgressionService.getTeamReportSummaries(teamId);
    }

    /**
     * Strips the paid fields unless the caller is entitled to see them.
     *
     * <p>The training percentage is a plus feature, visible for your own squad only (owner,
     * 2026-09-27). This endpoint takes a {@code teamId} with no ownership check, so without this
     * any manager could read any club's percentages by passing its id — which would hand over the
     * exact thing being paid for, and tell a rival how well its youth development is working.
     *
     * <p>Stripped to null rather than refused: the rest of the report is still legitimate, and a 403
     * would take away a page the manager can legitimately open.
     */
    /** Both halves of the owner's rule: a plus subscription, and it is the user's own club. */
    private boolean isEntitled(User user, Long teamId) {
        return plusFeatures.hasPlus(user) && plusFeatures.isOwnTeam(user, teamId);
    }

    /**
     * Strips the training percentage from anyone not entitled to it.
     *
     * <p>Delegated to {@code PlusFeatureService.trainingPercentOrNull} rather than re-deriving the
     * rule here. This controller used to hand-roll the same check the service already existed to
     * perform, which is precisely why that method had zero callers while the behaviour was correct —
     * the rule was applied twice in one place and nowhere else.
     *
     * <p>Nulled rather than refused: the rest of the report is still legitimate, and a 403 would take
     * away a page the manager can legitimately open.
     */
    private void applyVisibility(List<PlayerTrainingReportDTO> rows, User user, Long teamId) {
        boolean entitled = isEntitled(user, teamId);
        for (PlayerTrainingReportDTO row : rows) {
            if (row == null) continue;
            if (!entitled) {
                row.setTrainingPercent(null);
                continue;
            }
            // Round-trip through the service so there is exactly one implementation of the rule.
            row.setTrainingPercent(plusFeatures.trainingPercentOrNull(row.getTrainingPercent(), null, user, teamId));
        }
    }


    @GetMapping("/weekly/team/{teamId}/reports/{season}/{week}")
    public ResponseEntity<TrainingWeekReportDTO> getReport(@PathVariable Long teamId,
                                                          @PathVariable Integer season,
                                                          @PathVariable Integer week,
                                                          @AuthenticationPrincipal User user) {
        TrainingWeekReportDTO report = trainingProgressionService.getTeamReport(teamId, season, week);
        // 404, not 500. A week with no training run yet is a normal state the page has to render,
        // and a 500 made it look like the whole Training page was broken.
        if (report == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND",
                    "No training report for season " + season + ", week " + week
                            + ". Run training for that week first.");
        }
        applyVisibility(report.getPlayers(), user, teamId);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/weekly/team/{teamId}/player/{playerId}/reports/{season}/{week}")
    public ResponseEntity<PlayerTrainingReportDTO> getPlayerReport(@PathVariable Long teamId,
                                                                   @PathVariable Long playerId,
                                                                   @PathVariable Integer season,
                                                                   @PathVariable Integer week,
                                                                   @AuthenticationPrincipal User user) {
        PlayerTrainingReportDTO report =
                trainingProgressionService.getPlayerReport(teamId, playerId, season, week);
        if (report == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAYER_REPORT_NOT_FOUND",
                    "No training report for that player in season " + season + ", week " + week + ".");
        }
        if (!isEntitled(user, teamId)) {
            report.setTrainingPercent(null);
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/weekly/team/{teamId}/player/{playerId}/graph")
    public List<PlayerTrainingGraphPointDTO> getPlayerGraph(@PathVariable Long teamId, @PathVariable Long playerId) {
        return trainingProgressionService.getPlayerGraph(teamId, playerId);
    }
}
