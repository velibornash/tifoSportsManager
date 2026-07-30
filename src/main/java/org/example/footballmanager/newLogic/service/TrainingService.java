package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
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