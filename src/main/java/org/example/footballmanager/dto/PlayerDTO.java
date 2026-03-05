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

    public static PlayerDTO from(Player player) {
        return new PlayerDTO(
                player.getId(),
                player.getName(),
                player.getAge(),
                player.getPosition().name(),
                (int) player.getSkills().getRatingScore(player.getPosition()),
                player.getRating(),
                player.getForm(),
                player.getSkills().getFatigue(),
                player.getPlayerValue(),
                player.getSkills().getGoalkeeper(),
                player.getSkills().getPace(),
                player.getSkills().getStriker(),
                player.getSkills().getPassing(),
                player.getSkills().getTechnique(),
                player.getSkills().getDefender(),
                player.getSkills().getStamina(),
                player.getSkills().getPlaymaker(),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.GOALKEEPER),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.PACE),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.STRIKER),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.PASSING),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.TECHNIQUE),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.DEFENDER),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.STAMINA),
                player.getSkills().getExact(org.example.footballmanager.model.SkillName.PLAYMAKER),
                player.getTotalGoals(),
                player.getTotalAssists()//
        );
    }
}
