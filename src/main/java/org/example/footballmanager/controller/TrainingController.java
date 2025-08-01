package org.example.footballmanager.controller;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Training;
import org.example.footballmanager.repository.PlayerRepository;
import org.example.footballmanager.repository.TrainingRepository;
import org.example.footballmanager.service.PlayerSkillProgressionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/training")
public class TrainingController {

    private final TrainingRepository trainingRepository;
    private final PlayerRepository playerRepository;
    private final PlayerSkillProgressionService progressionService;


    public TrainingController(TrainingRepository trainingRepository, PlayerRepository playerRepository, PlayerSkillProgressionService progressionService) {
        this.trainingRepository = trainingRepository;
        this.playerRepository = playerRepository;
        this.progressionService = progressionService;
    }

    // Vraća sve treninge
    @GetMapping
    public List<Training> getAllTrainings() {
        return trainingRepository.findAll();
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
}