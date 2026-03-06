package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.footballmanager.model.Player;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerDTO {
    private Long id;
    private String name;
    private int age;
    private String position;
    private int overall;
    private int rating;
    private double form;
    private double fatigue;
    private double value;
    private int goalkeeper;
    private int pace;
    private int shooting;
    private int passing;
    private int technique;
    private int defending;
    private int stamina;
    private int playmaker;
    private double goalkeeperExact;
    private double paceExact;
    private double shootingExact;
    private double passingExact;
    private double techniqueExact;
    private double defendingExact;
    private double staminaExact;
    private double playmakerExact;
    private int totalGoals;
    private int totalAssists;
    private int injuryDaysRemaining;
    private boolean injured;

    public static PlayerDTO from(Player player) {
        PlayerDTO dto = new PlayerDTO();
        dto.setId(player.getId());
        dto.setName(player.getName());
        dto.setAge(player.getAge());
        dto.setPosition(player.getPosition().name());
        dto.setOverall((int) player.getSkills().getRatingScore(player.getPosition()));
        dto.setRating(player.getRating());
        dto.setForm(player.getForm());
        dto.setFatigue(player.getSkills().getFatigue());
        dto.setValue(player.getPlayerValue());
        dto.setGoalkeeper(player.getSkills().getGoalkeeper());
        dto.setPace(player.getSkills().getPace());
        dto.setShooting(player.getSkills().getStriker());
        dto.setPassing(player.getSkills().getPassing());
        dto.setTechnique(player.getSkills().getTechnique());
        dto.setDefending(player.getSkills().getDefender());
        dto.setStamina(player.getSkills().getStamina());
        dto.setPlaymaker(player.getSkills().getPlaymaker());
        dto.setGoalkeeperExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.GOALKEEPER));
        dto.setPaceExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.PACE));
        dto.setShootingExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.STRIKER));
        dto.setPassingExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.PASSING));
        dto.setTechniqueExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.TECHNIQUE));
        dto.setDefendingExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.DEFENDER));
        dto.setStaminaExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.STAMINA));
        dto.setPlaymakerExact(player.getSkills().getExact(org.example.footballmanager.model.SkillName.PLAYMAKER));
        dto.setTotalGoals(player.getTotalGoals());
        dto.setTotalAssists(player.getTotalAssists());
        dto.setInjuryDaysRemaining(player.getInjuryDaysRemaining());
        dto.setInjured(player.isInjured());
        return dto;
    }
}
