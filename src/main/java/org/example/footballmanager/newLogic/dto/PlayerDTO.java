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

    /**
     * The player's talent, 1-10 (Sprint 5.3, owner 2026-09-27).
     *
     * <p><b>Nullable is the whole mechanism.</b> Null means "this viewer may not see it" — not a talent
     * of zero, which is a different and much worse thing to show. The value is decided by
     * {@code PlusFeatureService.talentOrNull} at the controller and passed in; the DTO does not know
     * who is looking and cannot get the rule wrong.
     *
     * <p>An <b>exact figure</b> rather than a band, which is the owner's decision and the reason it is
     * coherent: the academy already reveals the exact value on promotion and carries it here, so a
     * manager who owns the player simply knows him. The band's job was to say how sure you were
     * <i>before</i> you committed. This is how scouting works — uncertainty is about other clubs.
     */
    private Double talent;

    public static PlayerDTO from(Player player) {
        return from(player, 0, null, null);
    }

    public static PlayerDTO from(Player player, int matchesPlayed, Double averageRating10) {
        return from(player, matchesPlayed, averageRating10, null);
    }

    /**
     * @param talent the exact talent if this viewer is entitled to it, otherwise <b>null</b>
     */
    public static PlayerDTO from(Player player, int matchesPlayed, Double averageRating10, Double talent) {
        PlayerDTO dto = new PlayerDTO();
        Position position = player.getPositionEnum() != null ? player.getPositionEnum() : Position.MID;
        dto.setId(player.getId());
        dto.setName(player.getName());
        dto.setAge(player.getAge());
        dto.setPosition(position.name());
        dto.setOverall(calculateOverall(player));
        dto.setTalent(talent);
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

        // The rating terms are bounded, and they have to be. The rating is a CAREER rating derived
        // from the same skills that already produced normalizedSkill, so an unbounded (rating - 62) / 5.5
        // was not measuring anything the skill term had not already measured - it was handing out
        // free points. In a world seeded with BASE_SKILL * 8 that was +6.2 to every bot in the
        // country, on top of the same bonus inside the defender and keeper role terms.
        //
        // What is left of it is a small, capped nudge for consistency between the stored rating and
        // the computed one, which can happen if a player's skills have moved since he was rated.
        double ratingNudge = player.getRating() > 0
                ? Math.max(-1.5, Math.min(1.5, (player.getRating() - 100.0 * normalizedSkill) / 20.0))
                : 0.0;

        double roleContribution = switch (position) {
            case ATT, WNG -> Math.min(12.0, player.getTotalGoals() * 0.65 + player.getTotalAssists() * 0.4);
            case MID -> Math.min(9.0, player.getTotalGoals() * 0.25 + player.getTotalAssists() * 0.55);
            case DEF -> Math.min(8.0, Math.max(0.0, player.getForm() - 6.0));
            case GK -> Math.min(9.0, Math.max(0.0, player.getForm() - 5.5) * 1.2);
        };

        double overall = 50.0 + normalizedSkill * 28.0 + formBoost + ratingNudge + roleContribution;
        return (int) Math.round(Math.max(45.0, Math.min(99.0, overall)));
    }

    /** Shared with {@link Player#careerRating()} so the two cannot drift apart. */
    private static double getMaxSkillScore(Position position) {
        return Player.maxRatingScore(position);
    }
}
