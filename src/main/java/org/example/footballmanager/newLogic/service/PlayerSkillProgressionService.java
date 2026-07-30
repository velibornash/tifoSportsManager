package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Training;
import org.example.footballmanager.newLogic.repository.TrainingRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Random;

@Service
public class PlayerSkillProgressionService {

    private final TrainingRepository trainingRepository;
    private final Random random = new Random();

    public PlayerSkillProgressionService(TrainingRepository trainingRepository) {
        this.trainingRepository = trainingRepository;
    }

    public Player trainPlayer(Player player) {
        Optional<Training> trainingOpt = trainingRepository.findByPlayerId(player.getId());
        if (trainingOpt.isEmpty()) return player;

        Training training = trainingOpt.get();

        double baseGrowth = 0.1;
        double talentFactor = (10 - player.getTalent()) / 10.0;
        double formFactor = player.getForm() / 10.0;
        double advancedMultiplier = training.isAdvanced() ? 1.5 : 1.0;

        Skills skills = player.getSkills();
        String position = training.getFormation(); // npr: DEF, MID, ATT

        switch (position.toUpperCase()) {
            case "GK" -> {
                skills.setGoalkeeper(increase(skills.getGoalkeeper(), baseGrowth, talentFactor, formFactor, advancedMultiplier));
            }
            case "DEF" -> {
                skills.setDefender(increase(skills.getDefender(), baseGrowth, talentFactor, formFactor, advancedMultiplier));
                skills.setPace(increase(skills.getPace(), baseGrowth * 0.5, talentFactor, formFactor, advancedMultiplier));
            }
            case "MID" -> {
                skills.setPassing(increase(skills.getPassing(), baseGrowth, talentFactor, formFactor, advancedMultiplier));
                skills.setPlaymaker(increase(skills.getPlaymaker(), baseGrowth * 0.5, talentFactor, formFactor, advancedMultiplier));
            }
            case "ATT" -> {
                skills.setStriker(increase(skills.getStriker(), baseGrowth, talentFactor, formFactor, advancedMultiplier));
                skills.setPace(increase(skills.getPace(), baseGrowth * 0.5, talentFactor, formFactor, advancedMultiplier));
            }
            default -> {
                // fallback: lagani napredak u izdržljivosti
                skills.setStamina(increase(skills.getStamina(), baseGrowth * 0.2, talentFactor, formFactor, advancedMultiplier));
            }
        }

        player.setSkills(skills);
        return player;
    }

    private int increase(int current, double base, double talent, double form, double multiplier) {
        if (current >= 17) return 17;
        if (random.nextDouble() > 0.9) return current;

        double resistance = (17.0 - current) / 17.0;
        double increment = base * talent * form * multiplier * resistance;
        int result = current + (int) Math.round(increment);

        return Math.min(result, 17);
    }
}