package org.example.footballmanager.service;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Position;
import org.example.footballmanager.model.Skills;
import org.example.footballmanager.repository.PlayerRepository;
import org.springframework.stereotype.Service;

@Service
public class TrainingService {
    private PlayerRepository playerRepository;
    public void trainTeam(Long teamId) {
        // TODO: implementirati trening
    }
    public void assignBasicTraining(Player player, Position position) {
        // Npr: ako je GK trenira Goalkeeping + Passing + Stamina
        Skills s = player.getSkills();
        s.setGoalkeeper(s.getGoalkeeper() + 1); // dummy logika
        s.setStamina(s.getStamina() + 1);
        playerRepository.save(player);
    }
}