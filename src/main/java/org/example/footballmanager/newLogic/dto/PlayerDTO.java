package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;

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
    private int matchesPlayed;
    private Double averageRating10;
    private int injuryDaysRemaining;
    private boolean injured;

    public static PlayerDTO from(Player player) {
        return from(player, 0, null);
    }

    public static PlayerDTO from(Player player, int matchesPlayed, Double averageRating10) {
        PlayerDTO dto = new PlayerDTO();
        Position position = player.getPositionEnum() != null ? player.getPositionEnum() : Position.MID;
        dto.setId(player.getId());
        dto.setName(player.getName());
        dto.setAge(player.getAge());
        dto.setPosition(position.name());
        dto.setOverall(calculateOverall(player));
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
        dto.setGoalkeeperExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.GOALKEEPER));
        dto.setPaceExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.PACE));
        dto.setShootingExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.STRIKER));
        dto.setPassingExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.PASSING));
        dto.setTechniqueExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.TECHNIQUE));
        dto.setDefendingExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.DEFENDER));
        dto.setStaminaExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.STAMINA));
        dto.setPlaymakerExact(player.getSkills().getExact(org.example.footballmanager.newLogic.model.SkillName.PLAYMAKER));
        dto.setTotalGoals(player.getTotalGoals());
        dto.setTotalAssists(player.getTotalAssists());
        dto.setMatchesPlayed(matchesPlayed);
        dto.setAverageRating10(averageRating10);
        dto.setInjuryDaysRemaining(player.getInjuryDaysRemaining());
        dto.setInjured(player.isInjured());
        return dto;
    }

    private static int calculateOverall(Player player) {
        Position position = player.getPositionEnum() != null ? player.getPositionEnum() : Position.MID;
        double skillBase = player.getSkills().getRatingScore(position);
        double normalizedSkill = Math.max(0.0, Math.min(1.0, skillBase / getMaxSkillScore(position)));
        double formBoost = (Math.max(1.0, Math.min(10.0, player.getForm())) - 5.5) * 1.8;
        double recentRatingBoost = player.getRating() > 0 ? (player.getRating() - 62.0) / 5.5 : 0.0;

        double roleContribution = switch (position) {
            case ATT, WNG -> Math.min(12.0, player.getTotalGoals() * 0.65 + player.getTotalAssists() * 0.4);
            case MID -> Math.min(9.0, player.getTotalGoals() * 0.25 + player.getTotalAssists() * 0.55);
            case DEF -> Math.min(8.0, Math.max(0.0, player.getRating() - 58.0) / 5.0 + Math.max(0.0, player.getForm() - 6.0));
            case GK -> Math.min(9.0, Math.max(0.0, player.getRating() - 55.0) / 4.5 + Math.max(0.0, player.getForm() - 5.5) * 1.2);
        };

        double overall = 50.0 + normalizedSkill * 28.0 + formBoost + recentRatingBoost + roleContribution;
        return (int) Math.round(Math.max(45.0, Math.min(99.0, overall)));
    }

    private static double getMaxSkillScore(Position position) {
        return switch (position) {
            case GK -> 76.5;
            case DEF -> 93.6;
            case MID -> 108.8;
            case ATT -> 102.0;
            case WNG -> 93.5;
        };
    }
}
