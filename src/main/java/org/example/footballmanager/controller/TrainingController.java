package org.example.footballmanager.controller;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Training;
import org.example.footballmanager.dto.training.PlayerTrainingGraphPointDTO;
import org.example.footballmanager.dto.training.PlayerTrainingReportDTO;
import org.example.footballmanager.dto.training.TrainingSetupDTO;
import org.example.footballmanager.dto.training.TrainingWeekReportDTO;
import org.example.footballmanager.dto.training.TrainingWeekSummaryDTO;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TrainingRepository;
import org.example.footballmanager.service.PlayerSkillProgressionService;
import org.example.footballmanager.service.TrainingProgressionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/training")
public class TrainingController {

    private final TrainingRepository trainingRepository;
    private final PlayerRepository playerRepository;
    private final PlayerSkillProgressionService progressionService;
    private final TrainingProgressionService trainingProgressionService;


    public TrainingController(TrainingRepository trainingRepository, PlayerRepository playerRepository, PlayerSkillProgressionService progressionService, TrainingProgressionService trainingProgressionService) {
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
        Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
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

    // Endpoint: Treniraj jednog igrača i vrati ga sa ažuriranim veštinama
    @PostMapping("/train/{playerId}")
    public Player trainPlayer(@PathVariable Long playerId) {
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        if (optionalPlayer.isEmpty()) {
            throw new RuntimeException("Player not found: " + playerId);
        }

        Player player = optionalPlayer.get();
        progressionService.trainPlayer(player);
        return playerRepository.save(player);
    }

    // Endpoint: Treniraj sve igrače u sistemu
    @PostMapping("/train-all")
    public List<Player> trainAllPlayers() {
        List<Player> players = playerRepository.findAll();
        players.forEach(progressionService::trainPlayer);
        return playerRepository.saveAll(players);
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

    @PostMapping("/weekly/team/{teamId}/run")
    public TrainingWeekReportDTO runWeeklyTraining(@PathVariable Long teamId) {
        return trainingProgressionService.runWeeklyTraining(teamId);
    }

    @GetMapping("/weekly/team/{teamId}/reports")
    public List<TrainingWeekSummaryDTO> getReportSummaries(@PathVariable Long teamId) {
        return trainingProgressionService.getTeamReportSummaries(teamId);
    }

    @GetMapping("/weekly/team/{teamId}/reports/{season}/{week}")
    public TrainingWeekReportDTO getReport(@PathVariable Long teamId, @PathVariable Integer season, @PathVariable Integer week) {
        return trainingProgressionService.getTeamReport(teamId, season, week);
    }

    @GetMapping("/weekly/team/{teamId}/player/{playerId}/reports/{season}/{week}")
    public PlayerTrainingReportDTO getPlayerReport(@PathVariable Long teamId, @PathVariable Long playerId,
                                                   @PathVariable Integer season, @PathVariable Integer week) {
        return trainingProgressionService.getPlayerReport(teamId, playerId, season, week);
    }

    @GetMapping("/weekly/team/{teamId}/player/{playerId}/graph")
    public List<PlayerTrainingGraphPointDTO> getPlayerGraph(@PathVariable Long teamId, @PathVariable Long playerId) {
        return trainingProgressionService.getPlayerGraph(teamId, playerId);
    }
}
